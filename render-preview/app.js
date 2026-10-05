const PRODUCTS = [
  {id:"acc-2018-rare",type:"Aged Account",title:"2018 Roblox Account",tag:"AGED",age:"2018",email:"Verified",inventory:"Rare",value:"$180+",price:3499,seller:"NovaVault",rating:4.98,sales:642,stock:4,symbol:"18",description:"Aged demo account listing with verified ownership-transfer status, established account history and a documented inventory summary."},
  {id:"acc-2019-premium",type:"Premium Inventory",title:"2019 Premium Inventory Account",tag:"PREMIUM",age:"2019",email:"Verified",inventory:"Premium",value:"$320+",price:5899,seller:"OrbitStock",rating:4.96,sales:381,stock:2,symbol:"19",description:"Premium demo account listing focused on account age, inventory value, verification status and clear transfer notes."},
  {id:"acc-2020-clean",type:"Full Access",title:"2020 Clean Full Access Account",tag:"FULL ACCESS",age:"2020",email:"Verified",inventory:"Clean",value:"$90+",price:2399,seller:"PixelDepot",rating:4.94,sales:911,stock:7,symbol:"20",description:"Demo full-access account listing with verified contact status and a clean inventory profile. Transfer is coordinated inside the order chat."},
  {id:"acc-2017-collector",type:"Aged Account",title:"2017 Collector Account",tag:"COLLECTOR",age:"2017",email:"Verified",inventory:"Collector",value:"$470+",price:7699,seller:"NovaVault",rating:4.98,sales:642,stock:1,symbol:"17",description:"Collector-style demo listing for buyers looking for an older account with a higher-value inventory summary."},
  {id:"acc-starter-2023",type:"Starter Account",title:"2023 Starter Account",tag:"STARTER",age:"2023",email:"Verified",inventory:"Starter",value:"$25+",price:899,seller:"QuickStock",rating:4.89,sales:1240,stock:18,symbol:"23",description:"Lower-cost starter demo account with verified status and simple inventory. Useful for testing the low-price purchase flow."},
  {id:"acc-2021-inventory",type:"Premium Inventory",title:"2021 Loaded Inventory Account",tag:"INVENTORY",age:"2021",email:"Verified",inventory:"Loaded",value:"$250+",price:4799,seller:"ArcadeHub",rating:4.97,sales:504,stock:3,symbol:"21",description:"Demo account with an inventory-focused presentation and documented estimated inventory value."},
  {id:"acc-2018-clean",type:"Full Access",title:"2018 Verified Full Access",tag:"VERIFIED",age:"2018",email:"Verified",inventory:"Standard",value:"$120+",price:3199,seller:"PixelDepot",rating:4.94,sales:911,stock:5,symbol:"18",description:"Aged demo account with a full-access transfer label, verified ownership status and clear post-purchase chat flow."},
  {id:"acc-2022-premium",type:"Premium Inventory",title:"2022 Premium Bundle Account",tag:"PREMIUM",age:"2022",email:"Verified",inventory:"Premium",value:"$160+",price:2899,seller:"OrbitStock",rating:4.96,sales:381,stock:6,symbol:"22",description:"Demo premium account with a summarized inventory bundle and verification metadata."},
  {id:"acc-2019-aged",type:"Aged Account",title:"2019 Aged Account · Clean History",tag:"AGED",age:"2019",email:"Verified",inventory:"Standard",value:"$85+",price:2099,seller:"QuickStock",rating:4.89,sales:1240,stock:8,symbol:"19",description:"Demo aged account with clean-history presentation and transparent stock details."},
  {id:"acc-2024-starter",type:"Starter Account",title:"2024 Fresh Starter Account",tag:"STARTER",age:"2024",email:"Verified",inventory:"Fresh",value:"$15+",price:599,seller:"QuickStock",rating:4.89,sales:1240,stock:24,symbol:"24",description:"Fresh low-cost demo account intended to show entry-level stock and quick seller communication."},
  {id:"acc-2016-rare",type:"Aged Account",title:"2016 Rare Age Account",tag:"RARE AGE",age:"2016",email:"Verified",inventory:"Standard",value:"$140+",price:4299,seller:"NovaVault",rating:4.98,sales:642,stock:2,symbol:"16",description:"Rare-age demo account with an older creation year and verified transfer metadata."},
  {id:"acc-2020-loaded",type:"Premium Inventory",title:"2020 Loaded Account · Premium Stock",tag:"LOADED",age:"2020",email:"Verified",inventory:"Loaded",value:"$390+",price:6499,seller:"ArcadeHub",rating:4.97,sales:504,stock:2,symbol:"20",description:"High-value demo inventory account with richer specs and a premium account card treatment."},
  {id:"acc-2021-clean",type:"Full Access",title:"2021 Full Access · Verified",tag:"FULL ACCESS",age:"2021",email:"Verified",inventory:"Standard",value:"$70+",price:1899,seller:"PixelDepot",rating:4.94,sales:911,stock:10,symbol:"21",description:"Mid-range demo full-access account with verified ownership-transfer status."},
  {id:"acc-2018-premium",type:"Premium Inventory",title:"2018 Premium Collector Stock",tag:"COLLECTOR",age:"2018",email:"Verified",inventory:"Collector",value:"$520+",price:8399,seller:"NovaVault",rating:4.98,sales:642,stock:1,symbol:"18",description:"Top-tier demo account card combining older age with a higher-value collector inventory summary."},
  {id:"acc-2022-clean",type:"Full Access",title:"2022 Clean Account · Full Access",tag:"CLEAN",age:"2022",email:"Verified",inventory:"Clean",value:"$55+",price:1499,seller:"OrbitStock",rating:4.96,sales:381,stock:12,symbol:"22",description:"Affordable demo full-access listing with verified status and clean inventory."}
];

const REVIEWS = [
  {name:"Dylan R.",order:"#NX-831044",text:"The layout made it really easy to compare the account age, stock and seller rating before messaging.",stars:5},
  {name:"Chris M.",order:"#NX-642118",text:"I liked that the order chat showed the payment status and delivery step in the same place.",stars:5},
  {name:"Avery K.",order:"#NX-517206",text:"Filters feel much cleaner than scrolling through a huge random list. The product details are easy to scan.",stars:5}
];

const FAQS = [
  ["How does a purchase work?","Choose an account, review its details, message the seller if needed, then complete the demo checkout. A shared order chat is automatically created for buyer and seller."],
  ["What happens after payment?","Nexora posts a payment-confirmed system message into the order chat. The seller can then mark the order as delivered and the buyer can confirm receipt."],
  ["Can I talk to a seller before buying?","Yes. Every product has a Message seller button that creates a pre-sale conversation without creating a paid order."],
  ["What does verified mean?","In this prototype, verified means the listing has an ownership-transfer status and seller verification badge. It does not expose session credentials or authentication tokens."],
  ["What if there is a problem with an order?","The buyer can open a dispute from the shared order chat. In a production marketplace, support would review evidence and conversation history."],
  ["Are these real accounts?","No. The current Render version is an interactive product and transaction prototype with demo inventory and no real payments."]
];

const state = {
  view:"store",
  search:"",
  type:"All",
  min:"",
  max:"",
  sort:"featured",
  selectedProduct:null,
  selectedChatKey:null,
  role:localStorage.getItem("nexora-stock-role") || "buyer",
  settings:loadJson("nexora-stock-settings",{messages:true,payments:true,delivery:true})
};

let chats = loadJson("nexora-stock-chats",[]);

const views = {
  store:document.getElementById("storeView"),
  support:document.getElementById("supportView"),
  chats:document.getElementById("chatsView"),
  settings:document.getElementById("settingsView")
};

function loadJson(key,fallback){
  try{
    const raw=localStorage.getItem(key);
    return raw?JSON.parse(raw):fallback;
  }catch{return fallback}
}
function saveChats(){localStorage.setItem("nexora-stock-chats",JSON.stringify(chats));updateBadge()}
function saveSettings(){localStorage.setItem("nexora-stock-settings",JSON.stringify(state.settings))}
function money(cents){return "$"+(cents/100).toFixed(2)}
function now(){return new Date().toLocaleTimeString([],{hour:"numeric",minute:"2-digit"})}
function productById(id){return PRODUCTS.find(p=>p.id===id)}
function escapeHtml(v){return String(v).replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;").replaceAll('"',"&quot;").replaceAll("'","&#039;")}
function escapeAttr(v){return escapeHtml(v)}
function updateBadge(){const b=document.getElementById("chatBadge");b.textContent=String(chats.length);b.style.display=chats.length?"inline-grid":"none"}

function showView(name){
  state.view=name;
  Object.entries(views).forEach(([key,el])=>el.classList.toggle("active",key===name));
  document.querySelectorAll("[data-view]").forEach(btn=>btn.classList.toggle("active",btn.dataset.view===name));
  if(name==="store")renderStore();
  if(name==="support")renderSupport();
  if(name==="chats")renderChats();
  if(name==="settings")renderSettings();
  window.scrollTo({top:0,behavior:"smooth"});
}

function filteredProducts(){
  let list=PRODUCTS.filter(p=>{
    const q=state.search.toLowerCase().trim();
    const searchOk=!q||p.title.toLowerCase().includes(q)||p.type.toLowerCase().includes(q)||p.seller.toLowerCase().includes(q)||p.age.includes(q);
    const typeOk=state.type==="All"||p.type===state.type;
    const minOk=!state.min||p.price>=Number(state.min)*100;
    const maxOk=!state.max||p.price<=Number(state.max)*100;
    return searchOk&&typeOk&&minOk&&maxOk;
  });
  if(state.sort==="price-low")list.sort((a,b)=>a.price-b.price);
  if(state.sort==="price-high")list.sort((a,b)=>b.price-a.price);
  if(state.sort==="oldest")list.sort((a,b)=>Number(a.age)-Number(b.age));
  if(state.sort==="rating")list.sort((a,b)=>b.rating-a.rating);
  return list;
}

function renderStore(){
  const list=filteredProducts();
  const totalStock=PRODUCTS.reduce((n,p)=>n+p.stock,0);
  views.store.innerHTML=`
    <section class="hero">
      <div class="shell hero-grid">
        <div>
          <span class="kicker">ROBLOX ACCOUNT STOCK · LIVE DEMO</span>
          <h1>Buy Roblox accounts with clarity.</h1>
          <p>Browse verified demo stock by account age, access type, inventory value and seller history. Message the seller first or purchase and continue the transaction in a shared order chat.</p>
          <div class="hero-actions">
            <button class="primary" data-scroll-target="catalog" type="button">Browse stock</button>
            <button class="secondary" data-scroll-target="faq" type="button">How it works</button>
          </div>
        </div>

        <aside class="hero-stock">
          <div class="live-row"><span class="live">LIVE STOCK</span><span class="last-restock">Restocked today</span></div>
          <div class="stat-grid">
            <div class="stat-card"><strong>1,284</strong><span>Demo sold</span></div>
            <div class="stat-card"><strong>${totalStock}</strong><span>In stock</span></div>
            <div class="stat-card"><strong>4.96</strong><span>Seller avg.</span></div>
          </div>
          <div class="stock-preview">
            ${PRODUCTS.slice(0,3).map(p=>`
              <button class="stock-line" data-product="${p.id}" type="button">
                <span class="mini-avatar">${p.symbol}</span>
                <span><strong>${escapeHtml(p.title)}</strong><span>${escapeHtml(p.type)} · ${p.stock} in stock</span></span>
                <b>${money(p.price)}</b>
              </button>
            `).join("")}
          </div>
        </aside>
      </div>
    </section>

    <section class="section" id="catalog">
      <div class="shell">
        <div class="section-head">
          <div><span class="kicker">CATALOG</span><h2>Live account stock</h2></div>
          <p>${list.length} listings matching filters</p>
        </div>

        <div class="catalog-toolbar">
          <div class="input-wrap"><span>⌕</span><input id="catalogSearch" value="${escapeAttr(state.search)}" placeholder="Search account, year or seller…"></div>
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
          ${["All","Full Access","Aged Account","Premium Inventory","Starter Account"].map(type=>`
            <button class="type-chip ${state.type===type?"active":""}" data-type="${type}" type="button">${type}</button>
          `).join("")}
        </div>

        <div class="product-grid">
          ${list.length?list.map(renderProductCard).join(""):'<div class="empty">No stock matches these filters.</div>'}
        </div>
      </div>
    </section>

    <section class="section" id="reviews">
      <div class="shell">
        <div class="section-head">
          <div><span class="kicker">REVIEWS</span><h2>Recent buyer feedback</h2></div>
          <p>Demo review content</p>
        </div>
        <div class="review-grid">
          ${REVIEWS.map(r=>`<article class="review"><div class="stars">${"★".repeat(r.stars)}</div><p>“${escapeHtml(r.text)}”</p><strong>${escapeHtml(r.name)}</strong><span>Order ${r.order}</span></article>`).join("")}
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
        <span class="account-symbol">${p.symbol}</span>
      </div>
      <div class="product-body">
        <h3>${escapeHtml(p.title)}</h3>
        <div class="product-sub">${escapeHtml(p.tag)} · ownership transfer verified</div>
        <div class="specs">
          <div class="spec"><span>Created</span><b>${p.age}</b></div>
          <div class="spec"><span>Email</span><b>${p.email}</b></div>
          <div class="spec"><span>Inventory</span><b>${p.inventory}</b></div>
        </div>
        <div class="product-foot">
          <div class="seller-mini"><strong>${escapeHtml(p.seller)} · ★ ${p.rating.toFixed(2)}</strong><span>${p.sales} completed sales</span></div>
          <strong class="price">${money(p.price)}</strong>
        </div>
      </div>
    </button>
  `;
}

function bindStore(){
  document.querySelectorAll("[data-product]").forEach(btn=>btn.addEventListener("click",()=>openProduct(productById(btn.dataset.product))));
  document.querySelectorAll("[data-scroll-target]").forEach(btn=>btn.addEventListener("click",()=>document.getElementById(btn.dataset.scrollTarget)?.scrollIntoView({behavior:"smooth"})));
  document.querySelectorAll("[data-type]").forEach(btn=>btn.addEventListener("click",()=>{state.type=btn.dataset.type;renderStore();document.getElementById("catalog")?.scrollIntoView()}));
  const search=document.getElementById("catalogSearch");
  search.addEventListener("input",e=>{state.search=e.target.value;renderStore();const next=document.getElementById("catalogSearch");next.focus();next.setSelectionRange(next.value.length,next.value.length)});
  document.getElementById("minPrice").addEventListener("change",e=>{state.min=e.target.value;renderStore()});
  document.getElementById("maxPrice").addEventListener("change",e=>{state.max=e.target.value;renderStore()});
  document.getElementById("sortSelect").addEventListener("change",e=>{state.sort=e.target.value;renderStore()});
  document.querySelectorAll("[data-faq]").forEach(btn=>btn.addEventListener("click",()=>btn.closest(".faq-item").classList.toggle("open")));
}

function openProduct(p){
  if(!p)return;
  state.selectedProduct=p;
  document.getElementById("productModalBody").innerHTML=`
    <div class="product-top">
      <div class="product-art">${p.symbol}</div>
      <div>
        <span class="kicker">${escapeHtml(p.type)} · ${p.stock} IN STOCK</span>
        <h2>${escapeHtml(p.title)}</h2>
        <div style="color:var(--muted);font-size:12px">Sold by <b style="color:var(--text)">${escapeHtml(p.seller)}</b> · ★ ${p.rating.toFixed(2)} · ${p.sales} completed sales</div>
      </div>
    </div>
    <div class="detail-grid">
      <div class="detail"><span>Created</span><b>${p.age}</b></div>
      <div class="detail"><span>Email</span><b>${p.email}</b></div>
      <div class="detail"><span>Inventory</span><b>${p.inventory}</b></div>
      <div class="detail"><span>Est. value</span><b>${p.value}</b></div>
    </div>
    <div class="description">${escapeHtml(p.description)} Account transfer details are coordinated through the shared order chat; this prototype does not expose authentication cookies or session tokens.</div>
    <div class="action-bar">
      <div><span style="color:var(--muted);font-size:11px">Total</span><div class="big-price">${money(p.price)}</div></div>
      <div class="action-buttons">
        <button class="secondary" id="messageSeller" type="button">Message seller</button>
        <button class="primary" id="buyNow" type="button">Buy now · demo</button>
      </div>
    </div>
  `;
  openModal("productModal");
  document.getElementById("messageSeller").onclick=()=>{const c=ensureChat(p,false);state.selectedChatKey=c.key;closeModal("productModal");showView("chats")};
  document.getElementById("buyNow").onclick=()=>{closeModal("productModal");openCheckout(p)};
}

function openCheckout(p){
  document.getElementById("checkoutModalBody").innerHTML=`
    <div class="notice">Prototype transaction only. No card is charged and no real account is transferred.</div>
    <h2 style="font:800 28px Manrope;margin:18px 0 8px">Review purchase</h2>
    <div class="summary-row"><span>${escapeHtml(p.title)}</span><b>${money(p.price)}</b></div>
    <div class="summary-row"><span>Seller</span><b>${escapeHtml(p.seller)}</b></div>
    <div class="summary-row"><span>Transfer</span><b>Shared order chat</b></div>
    <div class="summary-row"><span>Buyer protection</span><b>Demo enabled</b></div>
    <div class="action-bar"><button class="secondary" data-close-modal="checkoutModal">Cancel</button><button class="primary" id="completePurchase">Complete demo purchase</button></div>
  `;
  bindModalClose();
  openModal("checkoutModal");
  document.getElementById("completePurchase").onclick=()=>{
    const c=ensureChat(p,true);state.selectedChatKey=c.key;
    document.getElementById("checkoutModalBody").innerHTML=`<div class="success"><div class="success-icon">✓</div><h2>Payment confirmed</h2><p>Nexora created or upgraded the buyer–seller conversation into an order chat and posted the payment status automatically.</p><button class="primary" id="openOrderChat">Open order chat</button></div>`;
    document.getElementById("openOrderChat").onclick=()=>{closeModal("checkoutModal");showView("chats")};
  };
}

function chatKey(p){return [p.seller,p.id].join("|")}
function ensureChat(p,paid){
  const key=chatKey(p);
  let chat=chats.find(c=>c.key===key);
  if(!chat){
    chat={key,seller:p.seller,buyer:"DemoBuyer",productId:p.id,title:p.title,price:p.price,status:"PRE_SALE",orderId:null,messages:[]};
    chats.unshift(chat);
    chat.messages.push({sender:"system",text:"Conversation started about “"+p.title+"”. No payment has been made yet.",time:now()});
  }
  if(paid&&chat.status==="PRE_SALE"){
    chat.status="PAID";chat.orderId="NX-"+Math.floor(100000+Math.random()*900000);
    chat.messages.push({sender:"system",text:"Payment confirmed. DemoBuyer paid "+money(p.price)+" for “"+p.title+"”. Order "+chat.orderId+" is active. "+p.seller+" can now coordinate the ownership transfer in this chat.",time:now()});
  }
  saveChats();return chat;
}

function renderChats(){
  updateBadge();
  const selected=chats.find(c=>c.key===state.selectedChatKey)||chats[0]||null;
  if(selected)state.selectedChatKey=selected.key;
  views.chats.innerHTML=`
    <div class="shell page">
      <div class="page-title"><span class="kicker">ORDER COMMUNICATION</span><h1>Chats</h1><p>Ask questions before paying, then continue the same conversation after checkout. Payment and delivery status are posted by the site.</p></div>
      <section class="chat-layout">
        <aside class="thread-panel"><div class="thread-head"><strong>Conversations</strong><span>Viewing as ${state.role}</span></div><div class="thread-list">${chats.length?chats.map(c=>renderThread(c,selected)).join(""):'<div class="empty">No chats yet.</div>'}</div></aside>
        <section class="chat-pane">${selected?renderChatPane(selected):'<div class="empty">Open a product and message its seller.</div>'}</section>
      </section>
    </div>
  `;
  document.querySelectorAll("[data-chat]").forEach(btn=>btn.onclick=()=>{state.selectedChatKey=decodeURIComponent(btn.dataset.chat);renderChats()});
  if(selected)bindChat(selected);
}

function renderThread(c,selected){
  const other=state.role==="buyer"?c.seller:c.buyer,last=c.messages[c.messages.length-1];
  return `<button class="thread ${selected?.key===c.key?"active":""}" data-chat="${encodeURIComponent(c.key)}"><span class="avatar">${escapeHtml(other[0])}</span><span class="thread-copy"><strong>${escapeHtml(other)}</strong><span>${escapeHtml(last?.text||c.title)}</span></span><span class="thread-time">${escapeHtml(last?.time||"")}</span></button>`;
}

function renderChatPane(c){
  const other=state.role==="buyer"?c.seller:c.buyer,self=state.role==="buyer"?c.buyer:c.seller,paid=c.status!=="PRE_SALE";
  return `
    <div class="chat-top"><div class="chat-person"><span class="avatar">${escapeHtml(other[0])}</span><div><strong>${escapeHtml(other)}</strong><span>${escapeHtml(c.title)}</span></div></div><span class="status ${paid?"paid":""}">${statusLabel(c.status)}</span></div>
    ${paid?`<div class="order-strip"><div><b>${escapeHtml(c.orderId||"")}</b> · ${escapeHtml(c.title)}</div><div><span>Total </span><b>${money(c.price)}</b></div></div>${progress(c)}<div class="order-actions">${actions(c)}</div>`:""}
    <div class="messages" id="messages">${c.messages.map(m=>messageHtml(m,self)).join("")}</div>
    <form class="chat-form" id="chatForm"><input id="chatInput" placeholder="Message ${escapeAttr(other)}…" autocomplete="off"><button class="primary">Send</button></form>
  `;
}

function messageHtml(m,self){return m.sender==="system"?`<div class="message system">${escapeHtml(m.text)}<small>${escapeHtml(m.time)}</small></div>`:`<div class="message ${m.sender===self?"mine":""}">${escapeHtml(m.text)}<small>${escapeHtml(m.time)}</small></div>`}
function statusLabel(s){return {PRE_SALE:"Pre-sale",PAID:"Paid",DELIVERED:"Delivered",COMPLETED:"Completed",DISPUTED:"Disputed"}[s]||s}
function progress(c){const n={PAID:1,DELIVERED:2,COMPLETED:3,DISPUTED:1}[c.status]||0;return `<div class="order-progress">${[["Paid",1],["Delivered",2],["Completed",3]].map(([label,v])=>`<div class="order-step ${n>v||c.status==="COMPLETED"?"done":n===v?"current":""}">${label}</div>`).join("")}</div>`}
function actions(c){
  let out="";
  if(state.role==="seller"&&c.status==="PAID")out+='<button class="primary" id="deliverOrder">Mark delivered</button>';
  if(state.role==="buyer"&&c.status==="DELIVERED")out+='<button class="primary" id="confirmOrder">Confirm received</button>';
  if(state.role==="buyer"&&["PAID","DELIVERED"].includes(c.status))out+='<button class="danger" id="disputeOrder">Open dispute</button>';
  if(c.status==="COMPLETED")out+='<span class="status paid">Transaction completed</span>';
  if(c.status==="DISPUTED")out+='<span class="status">Dispute under review</span>';
  return out;
}
function bindChat(c){
  const msgs=document.getElementById("messages");if(msgs)msgs.scrollTop=msgs.scrollHeight;
  document.getElementById("chatForm").onsubmit=e=>{e.preventDefault();const input=document.getElementById("chatInput"),v=input.value.trim();if(!v)return;const sender=state.role==="buyer"?c.buyer:c.seller;c.messages.push({sender,text:v,time:now()});saveChats();renderChats()};
  const deliver=document.getElementById("deliverOrder");if(deliver)deliver.onclick=()=>{c.status="DELIVERED";c.messages.push({sender:"system",text:c.seller+" marked the account transfer as delivered. DemoBuyer can review and confirm receipt.",time:now()});saveChats();renderChats()};
  const confirm=document.getElementById("confirmOrder");if(confirm)confirm.onclick=()=>{c.status="COMPLETED";c.messages.push({sender:"system",text:"DemoBuyer confirmed receipt. The demo transaction is now completed.",time:now()});saveChats();renderChats()};
  const dispute=document.getElementById("disputeOrder");if(dispute)dispute.onclick=()=>{c.status="DISPUTED";c.messages.push({sender:"system",text:"A dispute was opened. Marketplace support would review the order chat and submitted evidence.",time:now()});saveChats();renderChats()};
}

function renderSupport(){
  views.support.innerHTML=`
    <div class="shell page">
      <div class="page-title"><span class="kicker">HELP CENTER</span><h1>Support</h1><p>Get help with marketplace questions, an order, or a seller conversation.</p></div>
      <div class="support-grid">
        <article class="panel"><h3>General support</h3><p>Questions about the marketplace, demo checkout or account features.</p><form class="form-grid demo-form"><input placeholder="Subject"><textarea placeholder="Describe what happened"></textarea><button class="primary">Send demo request</button></form></article>
        <article class="panel"><h3>Order issue</h3><p>Use the shared order chat first. If the issue cannot be resolved, open a dispute from the order actions.</p><div class="notice">For a production marketplace, support would have access to order status and conversation history, not users’ private authentication tokens.</div></article>
      </div>
    </div>
  `;
  document.querySelectorAll(".demo-form").forEach(f=>f.onsubmit=e=>{e.preventDefault();alert("Demo support request created.")});
}

function renderSettings(){
  views.settings.innerHTML=`
    <div class="shell page">
      <div class="page-title"><span class="kicker">ACCOUNT</span><h1>Settings</h1><p>Switch demo perspective and control transaction notifications.</p></div>
      <div class="settings-grid">
        <article class="panel"><h3>Demo perspective</h3><p>See the exact same chat as buyer or seller.</p><div class="role-switch"><button id="buyerRole" class="${state.role==="buyer"?"active":""}">Buyer</button><button id="sellerRole" class="${state.role==="seller"?"active":""}">Seller</button></div></article>
        <article class="panel"><h3>Notifications</h3><p>Choose which transaction events would notify you.</p>${toggle("messages","New messages")}${toggle("payments","Payment confirmed")}${toggle("delivery","Delivery updates")}</article>
        <article class="panel"><h3>Transaction model</h3><p>Every paid purchase upgrades the seller conversation into an order chat.</p><div class="notice">Checkout → payment confirmation → shared chat → delivery → buyer confirmation.</div></article>
        <article class="panel"><h3>Prototype data</h3><p>Demo chats and order statuses are stored only in this browser.</p><button class="danger" id="resetData">Reset demo data</button></article>
      </div>
    </div>
  `;
  document.getElementById("buyerRole").onclick=()=>setRole("buyer");document.getElementById("sellerRole").onclick=()=>setRole("seller");
  document.querySelectorAll("[data-toggle]").forEach(b=>b.onclick=()=>{state.settings[b.dataset.toggle]=!state.settings[b.dataset.toggle];saveSettings();renderSettings()});
  document.getElementById("resetData").onclick=()=>{chats=[];state.selectedChatKey=null;saveChats();renderSettings()};
}
function toggle(k,label){return `<div class="toggle-row"><span>${label}</span><button class="toggle ${state.settings[k]?"on":""}" data-toggle="${k}"><span></span></button></div>`}
function setRole(role){state.role=role;localStorage.setItem("nexora-stock-role",role);renderSettings()}

function openModal(id){const el=document.getElementById(id);el.classList.add("open");el.setAttribute("aria-hidden","false");bindModalClose()}
function closeModal(id){const el=document.getElementById(id);el.classList.remove("open");el.setAttribute("aria-hidden","true")}
function bindModalClose(){document.querySelectorAll("[data-close-modal]").forEach(b=>b.onclick=()=>closeModal(b.dataset.closeModal))}

document.getElementById("brandButton").onclick=()=>showView("store");
document.querySelectorAll("[data-view]").forEach(b=>b.onclick=()=>showView(b.dataset.view));
document.querySelectorAll("[data-scroll]").forEach(b=>b.onclick=()=>{showView("store");setTimeout(()=>document.getElementById(b.dataset.scroll)?.scrollIntoView({behavior:"smooth"}),0)});
document.querySelectorAll(".overlay").forEach(o=>o.onclick=e=>{if(e.target===o)closeModal(o.id)});
document.addEventListener("keydown",e=>{if(e.key==="Escape")document.querySelectorAll(".overlay.open").forEach(o=>closeModal(o.id))});
updateBadge();showView("store");