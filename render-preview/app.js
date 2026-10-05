const state = {
  view: "store",
  search: "",
  type: "All",
  min: "",
  max: "",
  sort: "featured",
  selectedProduct: null,
  selectedChatId: null,
  authMode: "login",
  pendingAfterAuth: null
};

let products = [];
let chats = [];
let currentUser = null;
let adminUsers = [];
let sessionToken = localStorage.getItem("novavault_session") || "";

const FAQS = [
  ["How does a purchase work?","Choose an account, review its details, message the seller if needed, then complete the demo checkout. A shared order chat is automatically created between your account and the seller."],
  ["What happens after payment?","NovaVault posts a payment-confirmed system message into the order chat. The seller can mark the order as delivered and the buyer can confirm receipt."],
  ["Can I talk to a seller before buying?","Yes. Every product has a Message seller button. You need to be logged in so the conversation is saved to your account."],
  ["How do I become a seller?","Every new account starts as Buyer only. Seller access is approved manually. To request seller access, contact @G1ddyk on Telegram."],
  ["What does NovaOfficial mean?","NovaOfficial is the official NovaVault seller identity. The Nova account has Seller and Admin access."],
  ["What if there is a problem with an order?","The buyer can open a dispute from the shared order chat. Support can then review the order status and conversation history."],
  ["Are these real purchases?","No. The current public version is an interactive marketplace prototype. Checkout is demo-only and does not charge real money."]
];

const REVIEWS = [
  {name:"Dylan R.",order:"#NX-831044",text:"The account filters make it easy to compare stock before opening a chat."},
  {name:"Chris M.",order:"#NX-642118",text:"The same chat continues from pre-sale questions into the order after checkout."},
  {name:"Avery K.",order:"#NX-517206",text:"I like seeing payment, delivery and completion status inside the conversation."}
];

const views = {
  store: document.getElementById("storeView"),
  support: document.getElementById("supportView"),
  chats: document.getElementById("chatsView"),
  settings: document.getElementById("settingsView"),
  seller: document.getElementById("sellerView"),
  admin: document.getElementById("adminView")
};

function money(cents){ return "$" + (Number(cents || 0) / 100).toFixed(2); }
function escapeHtml(v){ return String(v ?? "").replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;").replaceAll('"',"&quot;").replaceAll("'","&#039;"); }
function escapeAttr(v){ return escapeHtml(v); }
function hasRole(role){ return !!currentUser?.roles?.includes(role); }
function isSeller(){ return hasRole("seller") && currentUser?.sellerApproved; }
function isAdmin(){ return hasRole("admin"); }
function productById(id){ return products.find(p => String(p.id) === String(id)); }
function startYear(value){ const m=String(value||"").match(/\d{4}/); return m ? Number(m[0]) : 9999; }

async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (options.body && !headers["Content-Type"]) headers["Content-Type"] = "application/json";
  if (sessionToken) headers.Authorization = "Bearer " + sessionToken;
  const response = await fetch(path, { ...options, headers });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && sessionToken) {
      sessionToken = "";
      currentUser = null;
      localStorage.removeItem("novavault_session");
      updateAccountButton();
    }
    throw new Error(data.error || "Request failed");
  }
  return data;
}

async function loadProducts(){
  const data = await api("/api/products");
  products = (data.products || []).map(p => ({
    id:p.id,
    sellerId:p.sellerId,
    seller:p.sellerName || "Seller",
    sellerVerified:!!p.sellerVerified,
    type:p.type,
    title:p.title,
    tag:p.tag,
    age:p.ageLabel,
    email:p.transferLabel,
    inventory:p.inventoryLabel,
    value:p.valueLabel,
    price:Number(p.priceCents),
    stock:Number(p.stock),
    symbol:p.symbol,
    description:p.description,
    rating:p.sellerName==="NovaOfficial"?4.98:4.94,
    sales:p.sellerName==="NovaOfficial"?642:120
  }));
}

async function restoreSession(){
  if (!sessionToken) return;
  try {
    const data = await api("/api/me");
    currentUser = data.user || null;
  } catch {
    sessionToken = "";
    currentUser = null;
    localStorage.removeItem("novavault_session");
  }
}

function updateAccountButton(){
  const button=document.querySelector(".account-button");
  if(!button) return;
  if(!currentUser){
    button.textContent="Log in";
    return;
  }
  const suffix=isAdmin()?" · Admin":isSeller()?" · Seller":" · Buyer";
  button.textContent=currentUser.displayName + suffix;
}

function updateChatBadge(){
  const badge=document.getElementById("chatBadge");
  if(!badge) return;
  badge.textContent=String(chats.length);
  badge.style.display=chats.length?"inline-grid":"none";
}

function requireLogin(afterLogin=null){
  if(currentUser) return true;
  state.pendingAfterAuth=afterLogin;
  openAuth("login");
  return false;
}

function showView(name){
  if(name==="chats" && !currentUser){
    requireLogin(()=>showView("chats"));
    return;
  }
  if(name==="seller" && !isSeller()){
    showView("settings");
    return;
  }
  if(name==="admin" && !isAdmin()){
    showView("settings");
    return;
  }

  state.view=name;
  Object.entries(views).forEach(([key,el])=>el.classList.toggle("active",key===name));
  document.querySelectorAll("[data-view]").forEach(btn=>btn.classList.toggle("active",btn.dataset.view===name));

  if(name==="store") renderStore();
  if(name==="support") renderSupport();
  if(name==="chats") renderChats();
  if(name==="settings") renderSettings();
  if(name==="seller") renderSeller();
  if(name==="admin") renderAdmin();

  updateAccountButton();
  window.scrollTo({top:0,behavior:"smooth"});
}

function filteredProducts(){
  let list=products.filter(p=>{
    const q=state.search.toLowerCase().trim();
    const searchOk=!q||p.title.toLowerCase().includes(q)||p.type.toLowerCase().includes(q)||p.seller.toLowerCase().includes(q)||String(p.age).toLowerCase().includes(q)||String(p.value).toLowerCase().includes(q);
    const typeOk=state.type==="All"||p.type===state.type;
    const minOk=!state.min||p.price>=Number(state.min)*100;
    const maxOk=!state.max||p.price<=Number(state.max)*100;
    return searchOk&&typeOk&&minOk&&maxOk;
  });
  if(state.sort==="price-low")list.sort((a,b)=>a.price-b.price);
  if(state.sort==="price-high")list.sort((a,b)=>b.price-a.price);
  if(state.sort==="oldest")list.sort((a,b)=>startYear(a.age)-startYear(b.age));
  if(state.sort==="rating")list.sort((a,b)=>b.rating-a.rating);
  return list;
}

function renderStore(){
  const list=filteredProducts();
  const totalStock=products.reduce((n,p)=>n+p.stock,0);
  views.store.innerHTML=`
    <section class="hero">
      <div class="shell hero-grid">
        <div>
          <span class="kicker">NOVAVAULT · ROBLOX ACCOUNT STOCK</span>
          <h1>Easy accounts. Better value.</h1>
          <p>Browse account stock by Robux spent, gamepasses, Robux balance, inventory and account age. Log in to save chats and orders to your NovaVault account.</p>
          <div class="hero-actions">
            <button class="primary" data-scroll-target="catalog" type="button">Browse stock</button>
            ${currentUser
              ? '<button class="secondary" data-view="chats" type="button">Open my chats</button>'
              : '<button class="secondary" id="heroLogin" type="button">Log in / Sign up</button>'}
          </div>
        </div>
        <aside class="hero-stock">
          <div class="live-row"><span class="live">LIVE STOCK</span><span class="last-restock">Server inventory</span></div>
          <div class="stat-grid">
            <div class="stat-card"><strong>1,284</strong><span>Demo sold</span></div>
            <div class="stat-card"><strong>${totalStock}</strong><span>In stock</span></div>
            <div class="stat-card"><strong>4.98</strong><span>Official seller</span></div>
          </div>
          <div class="stock-preview">
            ${products.slice(0,3).map(p=>`
              <button class="stock-line" data-product="${p.id}" type="button">
                <span class="mini-avatar">${escapeHtml(p.symbol)}</span>
                <span><strong>${escapeHtml(p.title)}</strong><span>${escapeHtml(p.type)} · ${p.stock} in stock</span></span>
                <b>${money(p.price)}</b>
              </button>
            `).join("")}
          </div>
        </aside>
      </div>
    </section>

    <section class="trust-strip">
      <div class="shell trust-grid">
        <div><strong>Real account profiles</strong><span>Registration and login are server-backed instead of one shared browser profile.</span></div>
        <div><strong>Private conversations</strong><span>Your chats load only after you sign into the account that owns them.</span></div>
        <div><strong>Buyer by default</strong><span>Seller access is manually approved through @G1ddyk on Telegram.</span></div>
      </div>
    </section>

    <section class="section" id="catalog">
      <div class="shell">
        <div class="section-head">
          <div><span class="kicker">CATALOG</span><h2>Live account stock</h2></div>
          <p>${list.length} listings matching filters</p>
        </div>

        <div class="catalog-toolbar">
          <div class="input-wrap"><span>⌕</span><input id="catalogSearch" value="${escapeAttr(state.search)}" placeholder="Search spend, game, year or seller…"></div>
          <input class="filter-input" id="minPrice" type="number" min="0" value="${escapeAttr(state.min)}" placeholder="Min price">
          <input class="filter-input" id="maxPrice" type="number" min="0" value="${escapeAttr(state.max)}" placeholder="Max price">
          <select class="sort-select" id="sortSelect">
            <option value="featured" ${state.sort==="featured"?"selected":""}>Featured</option>
            <option value="price-low" ${state.sort==="price-low"?"selected":""}>Price: low to high</option>
            <option value="price-high" ${state.sort==="price-high"?"selected":""}>Price: high to low</option>
            <option value="oldest" ${state.sort==="oldest"?"selected":""}>Oldest accounts</option>
            <option value="rating" ${state.sort==="rating"?"selected":""}>Seller rating</option>
          </select>
        </div>

        <div class="type-row">
          ${["All","Robux Spent","Gamepasses","Robux Balance","Game Inventory","Aged + Spent"].map(type=>`
            <button class="type-chip ${state.type===type?"active":""}" data-type="${type}" type="button">${type}</button>
          `).join("")}
        </div>

        <div class="product-grid">
          ${list.length?list.map(renderProductCard).join(""):'<div class="empty">No stock matches these filters.</div>'}
        </div>
      </div>
    </section>

    <section class="section how-section" id="how">
      <div class="shell">
        <div class="section-head"><div><span class="kicker">HOW IT WORKS</span><h2>One account, all your deals</h2></div></div>
        <div class="how-grid">
          <article><span>01</span><h3>Create an account</h3><p>Register once. Your account starts as Buyer and can be used again on another device by logging in.</p></article>
          <article><span>02</span><h3>Message or buy</h3><p>Open a private pre-sale chat or complete demo checkout. The conversation is attached to your account.</p></article>
          <article><span>03</span><h3>Finish the order</h3><p>Payment, delivery, confirmation and disputes stay in the same buyer–seller conversation.</p></article>
        </div>
      </div>
    </section>

    <section class="section" id="reviews">
      <div class="shell">
        <div class="section-head"><div><span class="kicker">REVIEWS</span><h2>Recent buyer feedback</h2></div></div>
        <div class="review-grid">
          ${REVIEWS.map(r=>`<article class="review"><div class="stars">★★★★★</div><p>“${escapeHtml(r.text)}”</p><strong>${escapeHtml(r.name)}</strong><span>Order ${r.order}</span></article>`).join("")}
        </div>
      </div>
    </section>

    <section class="section" id="faq">
      <div class="shell">
        <div class="section-head"><div><span class="kicker">FAQ</span><h2>Questions before buying</h2></div></div>
        <div class="faq-list">
          ${FAQS.map((f,i)=>`<article class="faq-item"><button class="faq-q" data-faq="${i}" type="button"><span>${escapeHtml(f[0])}</span><span>+</span></button><div class="faq-a">${escapeHtml(f[1])}</div></article>`).join("")}
        </div>
      </div>
    </section>
  `;
  bindStore();
}

function renderProductCard(p){
  return `
    <button class="product-card" data-product="${p.id}" type="button">
      <div class="product-cover">
        <div class="product-cover-top"><span class="product-type">${escapeHtml(p.type)}</span><span class="stock-tag">${p.stock} IN STOCK</span></div>
        <span class="account-symbol">${escapeHtml(p.symbol)}</span>
      </div>
      <div class="product-body">
        <h3>${escapeHtml(p.title)}</h3>
        <div class="product-sub">${escapeHtml(p.tag)} · ${escapeHtml(p.email)}</div>
        <div class="specs">
          <div class="spec"><span>Account</span><b>${escapeHtml(p.age)}</b></div>
          <div class="spec"><span>Guaranteed</span><b>${escapeHtml(p.value)}</b></div>
          <div class="spec"><span>Stock</span><b>${p.stock} left</b></div>
        </div>
        <div class="product-foot">
          <div class="seller-mini">
            <strong>${escapeHtml(p.seller)} ${p.seller==="NovaOfficial"?'<span class="official-badge">Official</span>':""} · ★ ${p.rating.toFixed(2)}</strong>
            <span>${p.sales} completed sales</span>
          </div>
          <strong class="price">${money(p.price)}</strong>
        </div>
      </div>
    </button>
  `;
}

function bindStore(){
  document.querySelectorAll("[data-product]").forEach(btn=>btn.onclick=()=>openProduct(productById(btn.dataset.product)));
  document.querySelectorAll("[data-scroll-target]").forEach(btn=>btn.onclick=()=>document.getElementById(btn.dataset.scrollTarget)?.scrollIntoView({behavior:"smooth"}));
  document.querySelectorAll("[data-view]").forEach(btn=>btn.onclick=()=>showView(btn.dataset.view));
  document.querySelectorAll("[data-type]").forEach(btn=>btn.onclick=()=>{state.type=btn.dataset.type;renderStore();document.getElementById("catalog")?.scrollIntoView()});
  document.querySelectorAll("[data-faq]").forEach(btn=>btn.onclick=()=>btn.closest(".faq-item").classList.toggle("open"));
  const heroLogin=document.getElementById("heroLogin"); if(heroLogin) heroLogin.onclick=()=>openAuth("login");

  const search=document.getElementById("catalogSearch");
  search.oninput=e=>{state.search=e.target.value;renderStore();const next=document.getElementById("catalogSearch");next.focus();next.setSelectionRange(next.value.length,next.value.length)};
  document.getElementById("minPrice").onchange=e=>{state.min=e.target.value;renderStore()};
  document.getElementById("maxPrice").onchange=e=>{state.max=e.target.value;renderStore()};
  document.getElementById("sortSelect").onchange=e=>{state.sort=e.target.value;renderStore()};
}

function openProduct(p){
  if(!p) return;
  state.selectedProduct=p;
  document.getElementById("productModalBody").innerHTML=`
    <div class="product-top">
      <div class="product-art">${escapeHtml(p.symbol)}</div>
      <div>
        <span class="kicker">${escapeHtml(p.type)} · ${p.stock} IN STOCK</span>
        <h2>${escapeHtml(p.title)}</h2>
        <div style="color:var(--muted);font-size:12px">Sold by <b style="color:var(--text)">${escapeHtml(p.seller)}</b> ${p.seller==="NovaOfficial"?'<span class="official-badge">Approved Seller</span>':""} · ★ ${p.rating.toFixed(2)}</div>
      </div>
    </div>
    <div class="detail-grid">
      <div class="detail"><span>Account age</span><b>${escapeHtml(p.age)}</b></div>
      <div class="detail"><span>Guaranteed</span><b>${escapeHtml(p.value)}</b></div>
      <div class="detail"><span>Inventory</span><b>${escapeHtml(p.inventory)}</b></div>
      <div class="detail"><span>Transfer</span><b>${escapeHtml(p.email)}</b></div>
    </div>
    <div class="description">${escapeHtml(p.description)}</div>
    <div class="action-bar">
      <div><span style="color:var(--muted);font-size:11px">Total</span><div class="big-price">${money(p.price)}</div></div>
      <div class="action-buttons">
        <button class="secondary" id="messageSeller" type="button">Message seller</button>
        <button class="primary" id="buyNow" type="button">Buy now · demo</button>
      </div>
    </div>
  `;
  openModal("productModal");

  document.getElementById("messageSeller").onclick=async()=>{
    if(!requireLogin(()=>openProduct(p))) return;
    try{
      const data=await api("/api/chats",{method:"POST",body:JSON.stringify({productId:p.id})});
      state.selectedChatId=String(data.chatId);
      closeModal("productModal");
      showView("chats");
    }catch(e){showInlineError("productModalBody",e.message)}
  };
  document.getElementById("buyNow").onclick=()=>{
    if(!requireLogin(()=>openProduct(p))) return;
    closeModal("productModal");
    openCheckout(p);
  };
}

function openCheckout(p){
  document.getElementById("checkoutModalBody").innerHTML=`
    <div class="notice">Prototype transaction only. No real payment is charged.</div>
    <h2 style="font:800 28px Manrope;margin:18px 0 8px">Review purchase</h2>
    <div class="summary-row"><span>${escapeHtml(p.title)}</span><b>${money(p.price)}</b></div>
    <div class="summary-row"><span>Signed in as</span><b>${escapeHtml(currentUser.displayName)}</b></div>
    <div class="summary-row"><span>Seller</span><b>${escapeHtml(p.seller)}</b></div>
    <div class="summary-row"><span>Order chat</span><b>Created automatically</b></div>
    <div class="action-bar"><button class="secondary" data-close-modal="checkoutModal">Cancel</button><button class="primary" id="completePurchase">Complete demo purchase</button></div>
  `;
  bindModalClose();
  openModal("checkoutModal");

  document.getElementById("completePurchase").onclick=async()=>{
    const button=document.getElementById("completePurchase");
    button.disabled=true;button.textContent="Processing…";
    try{
      const data=await api("/api/orders/buy",{method:"POST",body:JSON.stringify({productId:p.id})});
      state.selectedChatId=String(data.chatId);
      await loadProducts();
      document.getElementById("checkoutModalBody").innerHTML=`
        <div class="success"><div class="success-icon">✓</div><h2>Payment confirmed</h2><p>The order is now attached to your NovaVault account and the shared buyer–seller chat.</p><button class="primary" id="openOrderChat">Open order chat</button></div>
      `;
      document.getElementById("openOrderChat").onclick=()=>{closeModal("checkoutModal");showView("chats")};
    }catch(e){
      button.disabled=false;button.textContent="Complete demo purchase";
      showInlineError("checkoutModalBody",e.message);
    }
  };
}

function openAuth(mode="login"){
  state.authMode=mode;
  const title=document.getElementById("authModalTitle");
  title.textContent=mode==="login"?"Log in to NovaVault":"Create NovaVault account";
  const body=document.getElementById("authModalBody");
  body.innerHTML = mode==="login" ? `
    <div class="auth-intro"><span class="kicker">WELCOME BACK</span><h2>Log in</h2><p>Your chats, orders and role are attached to your account.</p></div>
    <form class="form-grid auth-form" id="loginForm">
      <label>Username or email<input id="loginIdentifier" autocomplete="username" required placeholder="Username or email"></label>
      <label>Password<input id="loginPassword" type="password" autocomplete="current-password" required placeholder="Password"></label>
      <button class="primary" type="submit">Log in</button>
      <div class="auth-error" id="authError"></div>
    </form>
    <div class="auth-switch">New to NovaVault? <button id="switchToRegister" type="button">Create account</button></div>
  ` : `
    <div class="auth-intro"><span class="kicker">NEW BUYER ACCOUNT</span><h2>Create account</h2><p>All new accounts start as Buyer. Seller access is approved manually.</p></div>
    <form class="form-grid auth-form" id="registerForm">
      <label>Username<input id="registerUsername" autocomplete="username" minlength="3" maxlength="24" required placeholder="3–24 letters, numbers or _"></label>
      <label>Email<input id="registerEmail" type="email" autocomplete="email" required placeholder="you@example.com"></label>
      <label>Password<input id="registerPassword" type="password" autocomplete="new-password" minlength="8" required placeholder="At least 8 characters"></label>
      <button class="primary" type="submit">Create buyer account</button>
      <div class="auth-error" id="authError"></div>
    </form>
    <div class="auth-switch">Already registered? <button id="switchToLogin" type="button">Log in</button></div>
  `;
  bindAuth();
  openModal("authModal");
}

function bindAuth(){
  const toRegister=document.getElementById("switchToRegister"); if(toRegister) toRegister.onclick=()=>openAuth("register");
  const toLogin=document.getElementById("switchToLogin"); if(toLogin) toLogin.onclick=()=>openAuth("login");

  const login=document.getElementById("loginForm");
  if(login) login.onsubmit=async e=>{
    e.preventDefault();
    const error=document.getElementById("authError");error.textContent="";
    const button=login.querySelector("button[type=submit]");button.disabled=true;button.textContent="Logging in…";
    try{
      const data=await api("/api/auth/login",{method:"POST",body:JSON.stringify({identifier:document.getElementById("loginIdentifier").value.trim(),password:document.getElementById("loginPassword").value})});
      sessionToken=data.token;currentUser=data.user;localStorage.setItem("novavault_session",sessionToken);
      closeModal("authModal");updateAccountButton();await refreshChats();
      const pending=state.pendingAfterAuth;state.pendingAfterAuth=null;
      if(pending) pending(); else showView("settings");
    }catch(err){error.textContent=err.message;button.disabled=false;button.textContent="Log in"}
  };

  const register=document.getElementById("registerForm");
  if(register) register.onsubmit=async e=>{
    e.preventDefault();
    const error=document.getElementById("authError");error.textContent="";
    const button=register.querySelector("button[type=submit]");button.disabled=true;button.textContent="Creating account…";
    try{
      const data=await api("/api/auth/register",{method:"POST",body:JSON.stringify({username:document.getElementById("registerUsername").value.trim(),email:document.getElementById("registerEmail").value.trim(),password:document.getElementById("registerPassword").value})});
      sessionToken=data.token;currentUser=data.user;localStorage.setItem("novavault_session",sessionToken);
      closeModal("authModal");updateAccountButton();await refreshChats();
      const pending=state.pendingAfterAuth;state.pendingAfterAuth=null;
      if(pending) pending(); else showView("settings");
    }catch(err){error.textContent=err.message;button.disabled=false;button.textContent="Create buyer account"}
  };
}

async function refreshChats(){
  if(!currentUser){ chats=[];updateChatBadge();return; }
  try{
    const data=await api("/api/chats");
    chats=data.chats||[];
  }catch{ chats=[]; }
  updateChatBadge();
}

async function renderChats(){
  views.chats.innerHTML=`<div class="shell page"><div class="page-title"><span class="kicker">PRIVATE ACCOUNT CHATS</span><h1>Chats</h1><p>Loading conversations for ${escapeHtml(currentUser.displayName)}…</p></div></div>`;
  await refreshChats();
  const selected=chats.find(c=>String(c.id)===String(state.selectedChatId))||chats[0]||null;
  if(selected) state.selectedChatId=String(selected.id);

  views.chats.innerHTML=`
    <div class="shell page">
      <div class="page-title"><span class="kicker">PRIVATE ACCOUNT CHATS</span><h1>Chats</h1><p>Only conversations belonging to <b>${escapeHtml(currentUser.displayName)}</b> are loaded from the server.</p></div>
      <section class="chat-layout">
        <aside class="thread-panel">
          <div class="thread-head"><strong>Conversations</strong><span>${escapeHtml(currentUser.displayName)} · ${isSeller()?"seller":"buyer"}</span></div>
          <div class="thread-list">${chats.length?chats.map(c=>renderThread(c,selected)).join(""):'<div class="empty">No chats yet. Open a listing and message its seller.</div>'}</div>
        </aside>
        <section class="chat-pane">${selected?renderChatPane(selected):'<div class="empty">No conversation selected.</div>'}</section>
      </section>
    </div>
  `;

  document.querySelectorAll("[data-chat-id]").forEach(btn=>btn.onclick=()=>{state.selectedChatId=btn.dataset.chatId;renderChats()});
  if(selected) bindChat(selected);
}

function renderThread(c,selected){
  const mySellerSide=String(c.sellerId)===String(currentUser.id);
  const other=mySellerSide?c.buyerName:c.sellerName;
  const last=c.messages?.[c.messages.length-1];
  return `
    <button class="thread ${selected&&String(selected.id)===String(c.id)?"active":""}" data-chat-id="${c.id}" type="button">
      <span class="avatar">${escapeHtml((other||"?").slice(0,1).toUpperCase())}</span>
      <span class="thread-copy"><strong>${escapeHtml(other)}</strong><span>${escapeHtml(last?.body||c.productTitle)}</span></span>
      <span class="thread-time">${last?new Date(last.createdAt).toLocaleTimeString([],{hour:"numeric",minute:"2-digit"}):""}</span>
    </button>
  `;
}

function renderChatPane(c){
  const sellerSide=String(c.sellerId)===String(currentUser.id);
  const other=sellerSide?c.buyerName:c.sellerName;
  const paid=c.status!=="PRE_SALE";
  return `
    <div class="chat-top"><div class="chat-person"><span class="avatar">${escapeHtml((other||"?")[0])}</span><div><strong>${escapeHtml(other)}</strong><span>${escapeHtml(c.productTitle)}</span></div></div><span class="status ${paid?"paid":""}">${statusLabel(c.status)}</span></div>
    ${paid?`<div class="order-strip"><div><b>${escapeHtml(c.orderCode||"")}</b> · ${escapeHtml(c.productTitle)}</div><div><span>Total </span><b>${money(c.priceCents)}</b></div></div>${progress(c)}<div class="order-actions">${orderActions(c,sellerSide)}</div>`:""}
    <div class="messages" id="messages">${(c.messages||[]).map(m=>messageHtml(m)).join("")}</div>
    <form class="chat-form" id="chatForm"><input id="chatInput" maxlength="2000" placeholder="Message ${escapeAttr(other)}…" autocomplete="off"><button class="primary">Send</button></form>
  `;
}

function messageHtml(m){
  if(m.senderKind==="system") return `<div class="message system">${escapeHtml(m.body)}<small>${new Date(m.createdAt).toLocaleTimeString([],{hour:"numeric",minute:"2-digit"})}</small></div>`;
  const mine=String(m.senderId)===String(currentUser.id);
  return `<div class="message ${mine?"mine":""}">${escapeHtml(m.body)}<small>${new Date(m.createdAt).toLocaleTimeString([],{hour:"numeric",minute:"2-digit"})}</small></div>`;
}

function statusLabel(s){return {PRE_SALE:"Pre-sale",PAID:"Paid",DELIVERED:"Delivered",COMPLETED:"Completed",DISPUTED:"Disputed"}[s]||s}
function progress(c){const n={PAID:1,DELIVERED:2,COMPLETED:3,DISPUTED:1}[c.status]||0;return `<div class="order-progress">${[["Paid",1],["Delivered",2],["Completed",3]].map(([label,v])=>`<div class="order-step ${n>v||c.status==="COMPLETED"?"done":n===v?"current":""}">${label}</div>`).join("")}</div>`}
function orderActions(c,sellerSide){
  let out="";
  if(sellerSide&&c.status==="PAID") out+='<button class="primary" data-order-action="deliver">Mark delivered</button>';
  if(!sellerSide&&c.status==="DELIVERED") out+='<button class="primary" data-order-action="confirm">Confirm received</button>';
  if(!sellerSide&&["PAID","DELIVERED"].includes(c.status)) out+='<button class="danger" data-order-action="dispute">Open dispute</button>';
  if(c.status==="COMPLETED") out+='<span class="status paid">Transaction completed</span>';
  if(c.status==="DISPUTED") out+='<span class="status">Dispute under review</span>';
  return out;
}

function bindChat(c){
  const messages=document.getElementById("messages");if(messages)messages.scrollTop=messages.scrollHeight;
  document.getElementById("chatForm").onsubmit=async e=>{
    e.preventDefault();
    const input=document.getElementById("chatInput");const body=input.value.trim();if(!body)return;
    try{await api("/api/chats/"+c.id+"/messages",{method:"POST",body:JSON.stringify({body})});input.value="";await renderChats()}catch(err){alert(err.message)}
  };
  document.querySelectorAll("[data-order-action]").forEach(btn=>btn.onclick=async()=>{
    btn.disabled=true;
    try{await api("/api/chats/"+c.id+"/status",{method:"POST",body:JSON.stringify({action:btn.dataset.orderAction})});await renderChats()}catch(err){btn.disabled=false;alert(err.message)}
  });
}

function renderSettings(){
  if(!currentUser){
    views.settings.innerHTML=`
      <div class="shell page">
        <div class="page-title"><span class="kicker">ACCOUNT</span><h1>Log in or create an account</h1><p>NovaVault accounts keep chats, orders and marketplace roles tied to your login instead of one browser profile.</p></div>
        <div class="auth-landing">
          <article class="panel"><h3>Already registered?</h3><p>Log in with your username/email and password.</p><button class="primary" id="accountLogin">Log in</button></article>
          <article class="panel"><h3>New buyer?</h3><p>Create an account. New registrations receive Buyer access only.</p><button class="secondary" id="accountRegister">Create account</button></article>
        </div>
      </div>
    `;
    document.getElementById("accountLogin").onclick=()=>openAuth("login");
    document.getElementById("accountRegister").onclick=()=>openAuth("register");
    return;
  }

  const roleLabel=isAdmin()?"Admin + Seller":isSeller()?"Seller":"Buyer";
  views.settings.innerHTML=`
    <div class="shell page">
      <div class="page-title"><span class="kicker">ACCOUNT</span><h1>Your NovaVault account</h1><p>This profile is loaded from the server and can be accessed again by logging in.</p></div>
      <div class="account-hero">
        <div class="account-avatar">${escapeHtml(currentUser.displayName.slice(0,2).toUpperCase())}</div>
        <div><span class="kicker">${roleLabel.toUpperCase()}</span><h2>${escapeHtml(currentUser.displayName)}</h2><p>@${escapeHtml(currentUser.username)} · ${escapeHtml(currentUser.email)}</p></div>
        <div class="account-badges">${currentUser.roles.map(r=>'<span>'+escapeHtml(r)+'</span>').join("")}</div>
      </div>
      <div class="settings-grid">
        <article class="panel">
          <h3>Account access</h3>
          <p>Current marketplace role: <b>${roleLabel}</b>.</p>
          ${!isSeller()
            ? `<div class="seller-lock"><strong>Want to become a seller?</strong><span>Seller access is not automatic. Contact <b>@G1ddyk</b> on Telegram and request manual approval.</span><a class="primary telegram-link" href="https://t.me/G1ddyk" target="_blank" rel="noreferrer">Contact @G1ddyk</a></div>`
            : `<div class="seller-lock approved"><strong>Seller access approved</strong><span>Your seller tools are unlocked.</span><button class="primary" data-view="seller">Open seller dashboard</button></div>`}
        </article>
        <article class="panel"><h3>Saved data</h3><p>Chats, orders and seller status are stored server-side for this account.</p><button class="secondary" data-view="chats">Open my chats</button></article>
        ${isAdmin()?`<article class="panel admin-callout"><h3>Nova admin access</h3><p>This account has Admin + Seller access and sells official inventory under <b>NovaOfficial</b>.</p><button class="primary" data-view="admin">Open admin panel</button></article>`:""}
        <article class="panel"><h3>Session</h3><p>Log out on this device. Your account data remains on the server.</p><button class="danger" id="logoutButton">Log out</button></article>
      </div>
    </div>
  `;
  document.querySelectorAll("[data-view]").forEach(btn=>btn.onclick=()=>showView(btn.dataset.view));
  document.getElementById("logoutButton").onclick=logout;
}

async function logout(){
  try{await api("/api/auth/logout",{method:"POST"})}catch{}
  sessionToken="";currentUser=null;chats=[];localStorage.removeItem("novavault_session");state.selectedChatId=null;updateChatBadge();updateAccountButton();showView("store");
}

async function renderSeller(){
  if(!isSeller()){showView("settings");return}
  const mine=products.filter(p=>String(p.sellerId)===String(currentUser.id));
  views.seller.innerHTML=`
    <div class="shell page">
      <div class="page-title"><span class="kicker">SELLER DASHBOARD</span><h1>${escapeHtml(currentUser.sellerName||currentUser.displayName)} inventory</h1><p>Publish server-backed listings and manage seller conversations.</p></div>
      <div class="dashboard-stats">
        <div><span>Active listings</span><strong>${mine.length}</strong></div>
        <div><span>Total stock</span><strong>${mine.reduce((n,p)=>n+p.stock,0)}</strong></div>
        <div><span>Seller status</span><strong>Approved</strong></div>
        <div><span>Chats</span><strong>${chats.filter(c=>String(c.sellerId)===String(currentUser.id)).length}</strong></div>
      </div>
      <div class="seller-layout">
        <section class="panel">
          <div class="section-head compact"><div><span class="kicker">YOUR STOCK</span><h2>Listings</h2></div></div>
          <div class="seller-listings">${mine.length?mine.map(p=>`<article><span class="mini-avatar">${escapeHtml(p.symbol)}</span><div><strong>${escapeHtml(p.title)}</strong><span>${p.stock} in stock · ${money(p.price)}</span></div><span class="status paid">Active</span></article>`).join(""):'<div class="empty">No listings yet.</div>'}</div>
        </section>
        <section class="panel">
          <h3>Add listing</h3><p>The listing is written to server storage and published under ${escapeHtml(currentUser.sellerName||currentUser.displayName)}.</p>
          <form class="form-grid" id="sellerListingForm">
            <input id="sellerTitle" placeholder="Offer title" required>
            <select id="sellerType"><option>Robux Spent</option><option>Gamepasses</option><option>Robux Balance</option><option>Game Inventory</option><option>Aged + Spent</option></select>
            <input id="sellerGuarantee" placeholder="Guaranteed condition" required>
            <input id="sellerPrice" type="number" min="1" step="0.01" placeholder="Price USD" required>
            <input id="sellerStock" type="number" min="1" placeholder="Stock" required>
            <button class="primary">Publish listing</button>
            <div class="support-result" id="sellerPublishResult"></div>
          </form>
        </section>
      </div>
    </div>
  `;
  document.getElementById("sellerListingForm").onsubmit=async e=>{
    e.preventDefault();
    const button=e.target.querySelector("button[type=submit]");button.disabled=true;button.textContent="Publishing…";
    const result=document.getElementById("sellerPublishResult");
    try{
      await api("/api/products",{method:"POST",body:JSON.stringify({
        title:document.getElementById("sellerTitle").value.trim(),
        type:document.getElementById("sellerType").value,
        valueLabel:document.getElementById("sellerGuarantee").value.trim(),
        priceCents:Math.round(Number(document.getElementById("sellerPrice").value)*100),
        stock:Number(document.getElementById("sellerStock").value)
      })});
      await loadProducts();
      result.textContent="Listing published successfully.";result.classList.add("show");
      setTimeout(()=>renderSeller(),500);
    }catch(err){result.textContent=err.message;result.classList.add("show");button.disabled=false;button.textContent="Publish listing"}
  };
}

async function renderAdmin(){
  if(!isAdmin()){showView("settings");return}
  views.admin.innerHTML=`<div class="shell page"><div class="page-title"><span class="kicker">ADMIN PANEL</span><h1>NovaVault control room</h1><p>Loading registered users…</p></div></div>`;
  try{const data=await api("/api/admin/users");adminUsers=data.users||[]}catch{adminUsers=[]}
  views.admin.innerHTML=`
    <div class="shell page">
      <div class="page-title"><span class="kicker">ADMIN PANEL</span><h1>NovaVault control room</h1><p>Server-backed users, roles, listings and conversations.</p></div>
      <div class="dashboard-stats">
        <div><span>Registered users</span><strong>${adminUsers.length}</strong></div>
        <div><span>Approved sellers</span><strong>${adminUsers.filter(u=>u.sellerApproved).length}</strong></div>
        <div><span>Listings</span><strong>${products.length}</strong></div>
        <div><span>My chats</span><strong>${chats.length}</strong></div>
      </div>
      <div class="admin-grid">
        <section class="panel"><h3>User roles</h3><p>New registrations are Buyer-only.</p><div class="admin-users">${adminUsers.map(u=>`<article><div><strong>${escapeHtml(u.displayName)}</strong><span>@${escapeHtml(u.username)} · ${escapeHtml(u.email)}</span></div><div class="account-badges">${u.roles.map(r=>'<span>'+escapeHtml(r)+'</span>').join("")}</div></article>`).join("")}</div></section>
        <section class="panel"><h3>Official seller</h3><p>Nova publishes marketplace-owned stock under <b>NovaOfficial</b>.</p><button class="primary" data-view="seller">Open seller dashboard</button></section>
        <section class="panel"><h3>Seller applications</h3><p>Seller access stays manual. Applicants contact <b>@G1ddyk</b> on Telegram.</p><a class="secondary telegram-link" href="https://t.me/G1ddyk" target="_blank" rel="noreferrer">Open Telegram contact</a></section>
        <section class="panel"><h3>Account security</h3><p>Passwords are hashed on the server and are never returned to the browser after registration/login.</p></section>
      </div>
    </div>
  `;
  document.querySelectorAll("[data-view]").forEach(btn=>btn.onclick=()=>showView(btn.dataset.view));
}

function renderSupport(){
  views.support.innerHTML=`
    <div class="shell page">
      <div class="page-title"><span class="kicker">HELP CENTER</span><h1>Support</h1><p>Get help with the marketplace, your account, an order, or a seller conversation.</p></div>
      <div class="support-grid">
        <article class="panel"><h3>General support</h3><p>Questions about NovaVault or your account.</p><form class="form-grid demo-form"><input placeholder="Subject" required><textarea placeholder="Describe what happened" required></textarea><button class="primary">Send demo request</button><div class="support-result"></div></form></article>
        <article class="panel"><h3>Seller access</h3><p>All new accounts are Buyer-only. Contact <b>@G1ddyk</b> on Telegram for seller approval.</p><a class="primary telegram-link" href="https://t.me/G1ddyk" target="_blank" rel="noreferrer">Contact @G1ddyk</a></article>
      </div>
    </div>
  `;
  document.querySelectorAll(".demo-form").forEach(f=>f.onsubmit=e=>{e.preventDefault();const result=f.querySelector(".support-result");result.textContent="Demo support request created.";result.classList.add("show");f.reset()});
}

function showInlineError(containerId,message){
  const el=document.getElementById(containerId);
  if(!el) return;
  let error=el.querySelector(".inline-error");
  if(!error){error=document.createElement("div");error.className="inline-error";el.appendChild(error)}
  error.textContent=message;
}

function openModal(id){const el=document.getElementById(id);el.classList.add("open");el.setAttribute("aria-hidden","false");bindModalClose()}
function closeModal(id){const el=document.getElementById(id);el.classList.remove("open");el.setAttribute("aria-hidden","true")}
function bindModalClose(){document.querySelectorAll("[data-close-modal]").forEach(b=>b.onclick=()=>closeModal(b.dataset.closeModal))}

document.getElementById("brandButton").onclick=()=>showView("store");
document.querySelectorAll("[data-view]").forEach(btn=>btn.onclick=()=>{
  if(btn.classList.contains("account-button") && !currentUser){openAuth("login");return}
  showView(btn.dataset.view);
});
document.querySelectorAll("[data-scroll]").forEach(btn=>btn.onclick=()=>{showView("store");setTimeout(()=>document.getElementById(btn.dataset.scroll)?.scrollIntoView({behavior:"smooth"}),0)});
document.querySelectorAll(".overlay").forEach(o=>o.onclick=e=>{if(e.target===o)closeModal(o.id)});
document.addEventListener("keydown",e=>{if(e.key==="Escape")document.querySelectorAll(".overlay.open").forEach(o=>closeModal(o.id))});

(async function init(){
  try{await Promise.all([loadProducts(),restoreSession()])}catch(e){console.error(e)}
  if(currentUser) await refreshChats();
  updateAccountButton();
  updateChatBadge();
  showView("store");
})();