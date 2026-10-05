const PRODUCTS = [
  {id:"spent-10k",type:"Robux Spent",title:"10,000+ Robux Spent Account",tag:"10K+ SPENT",age:"2019–2024",email:"Transfer-ready",inventory:"Mixed",value:"10K+ R$ spent",price:1299,seller:"NovaVault Stock",rating:4.98,sales:642,stock:24,symbol:"10K",description:"Demo account stock where the account history shows more than 10,000 Robux spent across Roblox. Exact games and inventory can vary by account."},
  {id:"spent-25k",type:"Robux Spent",title:"25,000+ Robux Spent Account",tag:"25K+ SPENT",age:"2018–2024",email:"Transfer-ready",inventory:"Mixed+",value:"25K+ R$ spent",price:2199,seller:"NovaVault Stock",rating:4.98,sales:642,stock:13,symbol:"25K",description:"Higher-spend demo stock with at least 25,000 Robux spent historically. Intended to show a simple guaranteed-condition offer."},
  {id:"spent-50k",type:"Robux Spent",title:"50,000+ Robux Spent Account",tag:"50K+ SPENT",age:"2017–2023",email:"Transfer-ready",inventory:"Premium",value:"50K+ R$ spent",price:3899,seller:"NovaVault Stock",rating:4.98,sales:642,stock:7,symbol:"50K",description:"Premium demo stock with 50,000+ Robux total spend history and potentially richer game purchases or avatar inventory."},
  {id:"spent-100k",type:"Robux Spent",title:"100,000+ Robux Spent Account",tag:"100K+ SPENT",age:"2016–2023",email:"Transfer-ready",inventory:"High value",value:"100K+ R$ spent",price:6999,seller:"NovaVault Stock",rating:4.98,sales:642,stock:3,symbol:"100K",description:"Top-tier demo category based on historical Robux spend. This is a prototype listing with account transfer coordinated through the shared order chat."},
  {id:"bf-gamepasses",type:"Gamepasses",title:"Blox Fruits Gamepass Account",tag:"BLOX FRUITS",age:"2019–2024",email:"Transfer-ready",inventory:"Gamepasses",value:"Premium passes",price:2699,seller:"OrbitStock",rating:4.96,sales:381,stock:11,symbol:"BF",description:"Demo Blox Fruits account stock with paid gamepasses. A production listing would clearly state which passes are guaranteed."},
  {id:"bf-premium",type:"Gamepasses",title:"Blox Fruits Premium Account",tag:"PREMIUM BF",age:"2018–2023",email:"Transfer-ready",inventory:"Passes + extras",value:"Premium setup",price:4499,seller:"OrbitStock",rating:4.96,sales:381,stock:5,symbol:"BF+",description:"Higher-tier Blox Fruits demo stock combining gamepass history and stronger in-game progression."},
  {id:"robux-balance",type:"Robux Balance",title:"Account With Robux Balance",tag:"ROBUX BALANCE",age:"2020–2024",email:"Transfer-ready",inventory:"Balance + items",value:"Varies by stock",price:1799,seller:"QuickStock",rating:4.91,sales:1204,stock:16,symbol:"R$",description:"Demo category for accounts that include a stated Robux balance at listing time. The exact amount would be shown on each real listing."},
  {id:"robux-balance-plus",type:"Robux Balance",title:"High Robux Balance Account",tag:"HIGH BALANCE",age:"2018–2024",email:"Transfer-ready",inventory:"Higher balance",value:"Premium stock",price:3499,seller:"QuickStock",rating:4.91,sales:1204,stock:6,symbol:"R$+",description:"Higher-value demo balance stock for buyers looking for more Robux included with the account."},
  {id:"mm2-inventory",type:"Game Inventory",title:"Murder Mystery 2 Inventory Account",tag:"MM2 INVENTORY",age:"2018–2024",email:"Transfer-ready",inventory:"MM2 items",value:"Godlies / sets",price:2399,seller:"ArcadeHub",rating:4.97,sales:504,stock:9,symbol:"MM2",description:"Demo MM2 stock with a guaranteed inventory category. Exact godlies or sets would be listed clearly before purchase."},
  {id:"adopt-inventory",type:"Game Inventory",title:"Adopt Me Inventory Account",tag:"ADOPT ME",age:"2018–2024",email:"Transfer-ready",inventory:"Pets / items",value:"Mixed inventory",price:2899,seller:"ArcadeHub",rating:4.97,sales:504,stock:8,symbol:"AM",description:"Demo Adopt Me account stock with inventory-based value and clear item summaries."},
  {id:"premium-passes",type:"Gamepasses",title:"Premium Gamepasses Account",tag:"MULTI-GAME",age:"2017–2023",email:"Transfer-ready",inventory:"Multiple passes",value:"Cross-game",price:3299,seller:"PixelDepot",rating:4.94,sales:911,stock:10,symbol:"GP",description:"Demo multi-game account category focused on accounts that have paid passes across several Roblox experiences."},
  {id:"aged-spent",type:"Aged + Spent",title:"2018–2020 Account · 10,000+ Robux Spent",tag:"AGED + SPENT",age:"2018–2020",email:"Transfer-ready",inventory:"Mixed",value:"10K+ R$ spent",price:2499,seller:"NovaVault Stock",rating:4.98,sales:642,stock:12,symbol:"18+",description:"Demo offer combining older account age with a minimum historical Robux spend threshold."}
]

const REVIEWS = [
  {name:"Dylan R.",order:"#NX-831044",text:"The layout made it really easy to compare the account age, stock and seller rating before messaging.",stars:5},
  {name:"Chris M.",order:"#NX-642118",text:"I liked that the order chat showed the payment status and delivery step in the same place.",stars:5},
  {name:"Avery K.",order:"#NX-517206",text:"Filters feel much cleaner than scrolling through a huge random list. The product details are easy to scan.",stars:5}
];

const FAQS = [
  ["How does a purchase work?","Choose an account, review its details, message the seller if needed, then complete the demo checkout. A shared order chat is automatically created for buyer and seller."],
  ["What happens after payment?","NovaVault posts a payment-confirmed system message into the order chat. The seller can then mark the order as delivered and the buyer can confirm receipt."],
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
function startYear(value){const match=String(value).match(/\d{4}/);return match?Number(match[0]):9999}
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
  if(state.sort==="oldest")list.sort((a,b)=>startYear(a.age)-startYear(b.age));
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
          <span class="kicker">NOVAVAULT · ROBLOX ACCOUNT STOCK</span>
          <h1>NovaVault — Easy accounts. Better value.</h1>
          <p>Browse account stock by Robux spent, gamepasses, Robux balance, game inventory and account age. Message the seller first or purchase and continue the transaction in a shared order chat.</p>
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

    <section class="trust-strip">
      <div class="shell trust-grid">
        <div><strong>Clear guarantees</strong><span>Every offer states the minimum condition you are buying.</span></div>
        <div><strong>Chat before paying</strong><span>Ask the seller questions before turning a conversation into an order.</span></div>
        <div><strong>Tracked order status</strong><span>Payment, delivery and completion are shown inside the shared chat.</span></div>
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
        <div class="section-head">
          <div><span class="kicker">HOW IT WORKS</span><h2>From stock to completed order</h2></div>
          <p>Simple enough to understand in one glance</p>
        </div>
        <div class="how-grid">
          <article><span>01</span><h3>Choose stock</h3><p>Compare the guaranteed condition, account age, seller reputation, price and remaining stock.</p></article>
          <article><span>02</span><h3>Message or buy</h3><p>Ask the seller questions first, or complete checkout and automatically convert that conversation into an order chat.</p></article>
          <article><span>03</span><h3>Finish in chat</h3><p>NovaVault posts payment status, the seller marks delivery, and the buyer confirms receipt or opens a dispute.</p></article>
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
          <div class="spec"><span>Account</span><b>${p.age}</b></div>
          <div class="spec"><span>Guaranteed</span><b>${p.value}</b></div>
          <div class="spec"><span>Stock</span><b>${p.stock} left</b></div>
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
      <div class="detail"><span>Account age</span><b>${p.age}</b></div>
      <div class="detail"><span>Guaranteed</span><b>${p.value}</b></div>
      <div class="detail"><span>Inventory</span><b>${p.inventory}</b></div>
      <div class="detail"><span>Transfer</span><b>${p.email}</b></div>
    </div>
    <div class="description">${escapeHtml(p.description)} Account transfer details are coordinated through the shared order chat, with the purchase status tracked by NovaVault.</div>
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
    document.getElementById("checkoutModalBody").innerHTML=`<div class="success"><div class="success-icon">✓</div><h2>Payment confirmed</h2><p>NovaVault created or upgraded the buyer–seller conversation into an order chat and posted the payment status automatically.</p><button class="primary" id="openOrderChat">Open order chat</button></div>`;
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
        <article class="panel"><h3>General support</h3><p>Questions about the marketplace, demo checkout or account features.</p><form class="form-grid demo-form"><input placeholder="Subject" required><textarea placeholder="Describe what happened" required></textarea><button class="primary">Send demo request</button><div class="support-result" aria-live="polite"></div></form></article>
        <article class="panel"><h3>Order issue</h3><p>Use the shared order chat first. If the issue cannot be resolved, open a dispute from the order actions.</p><div class="notice">For a production marketplace, support would have access to order status and conversation history, not users’ private authentication tokens.</div></article>
      </div>
    </div>
  `;
  document.querySelectorAll(".demo-form").forEach(f=>f.onsubmit=e=>{e.preventDefault();const result=f.querySelector(".support-result");if(result){result.textContent="Demo request created. In production, this would appear in your support inbox.";result.classList.add("show")}f.reset()});
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