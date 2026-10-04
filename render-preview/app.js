const GAMES = [
  ["Blox Fruits","BF","Popular"],["Adopt Me!","AM","Popular"],["Brookhaven RP","BR","Roleplay"],
  ["Pet Simulator 99","PS","Simulator"],["Murder Mystery 2","MM","Popular"],["Dress to Impress","DT","Popular"],
  ["The Strongest Battlegrounds","TS","Fighting"],["Blade Ball","BB","Fighting"],["Fisch","FI","Adventure"],
  ["Anime Vanguards","AV","Anime"],["Anime Defenders","AD","Anime"],["Anime Last Stand","AL","Anime"],
  ["Jujutsu Infinite","JI","Anime"],["Blue Lock: Rivals","BL","Anime"],["Type Soul","TS","Anime"],
  ["A Universal Time","AU","Anime"],["King Legacy","KL","Adventure"],["Fruit Battlegrounds","FB","Fighting"],
  ["BedWars","BW","Fighting"],["Arsenal","AR","Shooter"],["RIVALS","RV","Shooter"],["Da Hood","DH","Roleplay"],
  ["Welcome to Bloxburg","WB","Roleplay"],["Royale High","RH","Roleplay"],["Berry Avenue","BA","Roleplay"],
  ["Doors","DO","Horror"],["Pressure","PR","Horror"],["Piggy","PG","Horror"],["Evade","EV","Horror"],
  ["Tower of Hell","TH","Adventure"],["Bee Swarm Simulator","BS","Simulator"],["Grow a Garden","GG","Simulator"],
  ["Arm Wrestle Simulator","AW","Simulator"],["Sol's RNG","SR","Simulator"],["Driving Empire","DE","Adventure"],
  ["Jailbreak","JB","Adventure"]
];

const GENRES = ["All","Popular","Anime","Fighting","Simulator","Roleplay","Horror","Adventure","Shooter"];

const SELLERS = [
  {name:"NovaDeals",rating:4.98,sales:438},
  {name:"PixelVault",rating:4.95,sales:1204},
  {name:"LootRoom",rating:4.91,sales:817},
  {name:"OrbitShop",rating:4.88,sales:301},
  {name:"BlockForge",rating:5.00,sales:92},
  {name:"ArcadeHub",rating:4.96,sales:623}
];

const CATEGORY_TEMPLATES = {
  Account: [
    ["Progressed account","Progressed"],
    ["High level account","High Level"]
  ],
  Item: [
    ["Rare item bundle","Rare Items"],
    ["Premium item pack","Premium Bundle"]
  ],
  Service: [
    ["Fast delivery service","Fast Delivery"],
    ["Coaching / carry","Coaching"]
  ]
};

function loadJson(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function loadSettings() {
  const value = loadJson("nexora-demo-settings", null);
  return {
    messages: value?.messages !== false,
    payments: value?.payments !== false,
    delivery: value?.delivery !== false
  };
}

const state = {
  view: "marketplace",
  genre: "All",
  query: "",
  currentGame: null,
  gameCategory: "All",
  gameSearch: "",
  gameSort: "recommended",
  selectedOffer: null,
  selectedChatKey: null,
  role: localStorage.getItem("nexora-demo-role") || "buyer",
  settings: loadSettings()
};

let conversations = loadJson("nexora-demo-chats", []);

const views = {
  marketplace: document.getElementById("marketplaceView"),
  game: document.getElementById("gameView"),
  chats: document.getElementById("chatsView"),
  settings: document.getElementById("settingsView")
};

const navButtons = {
  marketplace: document.getElementById("navMarketplace"),
  chats: document.getElementById("navChats"),
  settings: document.getElementById("navSettings")
};

function money(cents) {
  return "$" + (cents / 100).toFixed(2);
}

function nowTime() {
  return new Date().toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

function seedFromName(name) {
  let seed = 0;
  for (let i = 0; i < name.length; i++) seed += name.charCodeAt(i) * (i + 1);
  return seed;
}

function slugify(value) {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
}

function gameByName(name) {
  return GAMES.find(game => game[0] === name);
}

function generateOffers(game) {
  const seed = seedFromName(game[0]);
  const sequence = ["Account","Item","Service","Item","Account","Service"];

  return sequence.map((category, index) => {
    const templates = CATEGORY_TEMPLATES[category];
    const template = templates[(seed + index) % templates.length];
    const seller = SELLERS[(seed + index * 2) % SELLERS.length];
    const price = 900 + ((seed * (index + 5)) % 7600);
    const delivery = index % 3 === 0 ? "Within 15 minutes" : index % 3 === 1 ? "Within 30 minutes" : "Within 24 hours";

    return {
      id: slugify(game[0]) + "-" + index,
      game: game[0],
      initials: game[1],
      genre: game[2],
      category,
      subcategory: template[1],
      title: template[0] + " · " + game[0],
      seller: seller.name,
      rating: seller.rating,
      sales: seller.sales,
      price,
      delivery,
      description:
        "Demo " + category.toLowerCase() + " offer for " + game[0] +
        ". A real listing would clearly explain exactly what the buyer receives, how delivery works, requirements, exclusions, and what happens if the order needs support."
    };
  });
}

function allOffers() {
  return GAMES.flatMap(generateOffers);
}

function findOfferById(id) {
  return allOffers().find(offer => offer.id === id);
}

function saveChats() {
  localStorage.setItem("nexora-demo-chats", JSON.stringify(conversations));
  updateChatBadge();
}

function saveSettings() {
  localStorage.setItem("nexora-demo-settings", JSON.stringify(state.settings));
}

function updateChatBadge() {
  const badge = document.getElementById("chatBadge");
  badge.textContent = String(conversations.length);
  badge.style.display = conversations.length ? "inline-grid" : "none";
}

function activateView(viewName) {
  state.view = viewName;
  Object.entries(views).forEach(([name, element]) => {
    element.classList.toggle("active", name === viewName);
  });

  Object.entries(navButtons).forEach(([name, button]) => {
    button.classList.toggle("active", name === viewName || (viewName === "game" && name === "marketplace"));
  });

  if (viewName === "marketplace") renderMarketplace();
  if (viewName === "game") renderGameView();
  if (viewName === "chats") renderChats();
  if (viewName === "settings") renderSettings();

  window.scrollTo({ top: 0, behavior: "smooth" });
}

function renderMarketplace() {
  const filteredGames = GAMES.filter(game => {
    const matchesGenre = state.genre === "All" || game[2] === state.genre;
    const matchesSearch = game[0].toLowerCase().includes(state.query.toLowerCase());
    return matchesGenre && matchesSearch;
  });

  const featured = [
    findOfferById("blox-fruits-0"),
    findOfferById("adopt-me-1"),
    findOfferById("murder-mystery-2-3"),
    findOfferById("blade-ball-1"),
    findOfferById("grow-a-garden-3"),
    findOfferById("rivals-2")
  ].filter(Boolean);

  views.marketplace.innerHTML = `
    <div class="shell">
      <section class="hero">
        <div>
          <span class="eyebrow">ROBLOX MARKETPLACE · INTERACTIVE DEMO</span>
          <h1>Find the game.<br>Find the deal.</h1>
          <p>Choose a Roblox experience, compare offers, message the seller before buying, or complete a demo purchase and continue the deal inside the order chat.</p>
          <div class="search-box">
            <span>⌕</span>
            <input id="gameSearch" value="${escapeHtml(state.query)}" placeholder="Search 36 Roblox experiences…" autocomplete="off">
          </div>
        </div>
        <aside class="hero-card">
          <span class="eyebrow">TRANSACTION FLOW</span>
          <h3>Chat is the center of every deal.</h3>
          <div class="stat-grid">
            <div class="stat"><strong>36</strong><span>Roblox experiences</span></div>
            <div class="stat"><strong>216</strong><span>demo offers</span></div>
            <div class="stat"><strong>0</strong><span>real charges</span></div>
          </div>
        </aside>
      </section>

      <div class="genre-row" id="genreRow">
        ${GENRES.map(genre => `<button class="pill ${state.genre === genre ? "active" : ""}" data-genre="${genre}" type="button">${genre}</button>`).join("")}
      </div>

      <div class="section-head">
        <div>
          <span class="eyebrow">DISCOVER GAMES</span>
          <h2>Choose an experience</h2>
        </div>
        <span>${filteredGames.length} games</span>
      </div>

      <section class="game-grid" id="gameGrid">
        ${filteredGames.map(game => `
          <button class="game-card" data-game="${escapeAttr(game[0])}" type="button">
            <span class="game-art">${game[1]}</span>
            <h3>${escapeHtml(game[0])}</h3>
            <p>${game[2]} Roblox experience</p>
            <span class="game-meta"><b>6 demo offers</b><span>Open →</span></span>
          </button>
        `).join("")}
      </section>

      <section style="margin:48px 0 70px">
        <div class="section-head">
          <div>
            <span class="eyebrow">POPULAR DEMO OFFERS</span>
            <h2>Try a complete deal</h2>
          </div>
          <span>Click any offer</span>
        </div>
        <div class="offer-list">
          ${featured.map(renderOfferRow).join("")}
        </div>
      </section>
    </div>
  `;

  document.getElementById("gameSearch").addEventListener("input", event => {
    state.query = event.target.value;
    renderMarketplace();
    const input = document.getElementById("gameSearch");
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
  });

  document.querySelectorAll("[data-genre]").forEach(button => {
    button.addEventListener("click", () => {
      state.genre = button.dataset.genre;
      renderMarketplace();
    });
  });

  document.querySelectorAll("[data-game]").forEach(button => {
    button.addEventListener("click", () => openGame(button.dataset.game));
  });

  attachOfferRowEvents();
}

function renderOfferRow(offer) {
  return `
    <button class="offer-row" data-offer-id="${offer.id}" type="button">
      <span class="offer-art">${offer.initials}</span>
      <span class="offer-main">
        <h3>${escapeHtml(offer.title)}</h3>
        <small>${escapeHtml(offer.game)} · ${offer.category} · ${escapeHtml(offer.subcategory)}</small>
      </span>
      <span class="seller-cell">
        <strong>${escapeHtml(offer.seller)}</strong>
        <span>★ ${offer.rating.toFixed(2)} · ${offer.sales} sales</span>
      </span>
      <strong class="price">${money(offer.price)}</strong>
    </button>
  `;
}

function attachOfferRowEvents() {
  document.querySelectorAll("[data-offer-id]").forEach(button => {
    button.addEventListener("click", () => {
      const offer = findOfferById(button.dataset.offerId);
      if (offer) openOfferModal(offer);
    });
  });
}

function openGame(name) {
  const game = gameByName(name);
  if (!game) return;
  state.currentGame = game;
  state.gameCategory = "All";
  state.gameSearch = "";
  state.gameSort = "recommended";
  activateView("game");
}

function renderGameView() {
  if (!state.currentGame) {
    activateView("marketplace");
    return;
  }

  const game = state.currentGame;
  let offers = generateOffers(game).filter(offer => {
    const matchesCategory = state.gameCategory === "All" || offer.category === state.gameCategory;
    const q = state.gameSearch.trim().toLowerCase();
    const matchesSearch = !q ||
      offer.title.toLowerCase().includes(q) ||
      offer.subcategory.toLowerCase().includes(q) ||
      offer.seller.toLowerCase().includes(q);
    return matchesCategory && matchesSearch;
  });

  if (state.gameSort === "price-low") offers.sort((a,b) => a.price - b.price);
  if (state.gameSort === "price-high") offers.sort((a,b) => b.price - a.price);
  if (state.gameSort === "rating") offers.sort((a,b) => b.rating - a.rating);
  if (state.gameSort === "sales") offers.sort((a,b) => b.sales - a.sales);

  views.game.innerHTML = `
    <div class="shell page-wrap">
      <button class="back-button" id="backToGames" type="button">← All games</button>

      <section class="game-hero">
        <div class="game-title">
          <span class="eyebrow">ROBLOX EXPERIENCE</span>
          <h1>${escapeHtml(game[0])}</h1>
          <p>Compare accounts, items and services. Message the seller before buying, or pay in the demo and continue delivery inside an automatically created order chat.</p>
        </div>
        <div class="game-mark">${game[1]}</div>
      </section>

      <div class="category-row">
        ${["All","Account","Item","Service"].map(category => `
          <button class="pill ${state.gameCategory === category ? "active" : ""}" data-category="${category}" type="button">
            ${category === "All" ? "All offers" : category + "s"}
          </button>
        `).join("")}
      </div>

      <div class="filter-bar">
        <div class="search-box">
          <span>⌕</span>
          <input id="offerSearch" value="${escapeHtml(state.gameSearch)}" placeholder="Search offers, subcategories, sellers…" autocomplete="off">
        </div>
        <select class="select" id="offerSort">
          <option value="recommended" ${state.gameSort === "recommended" ? "selected" : ""}>Recommended</option>
          <option value="price-low" ${state.gameSort === "price-low" ? "selected" : ""}>Price: low to high</option>
          <option value="price-high" ${state.gameSort === "price-high" ? "selected" : ""}>Price: high to low</option>
          <option value="rating" ${state.gameSort === "rating" ? "selected" : ""}>Seller rating</option>
          <option value="sales" ${state.gameSort === "sales" ? "selected" : ""}>Seller sales</option>
        </select>
      </div>

      <div class="section-head">
        <div><span class="eyebrow">LIVE DEMO MARKET</span><h2>${offers.length} matching offers</h2></div>
        <span>Demo inventory</span>
      </div>

      <div class="offer-list">
        ${offers.length ? offers.map(renderOfferRow).join("") : '<div class="empty-state"><div><strong>No matching offers</strong><p>Try another filter.</p></div></div>'}
      </div>
    </div>
  `;

  document.getElementById("backToGames").addEventListener("click", () => activateView("marketplace"));

  document.querySelectorAll("[data-category]").forEach(button => {
    button.addEventListener("click", () => {
      state.gameCategory = button.dataset.category;
      renderGameView();
    });
  });

  document.getElementById("offerSearch").addEventListener("input", event => {
    state.gameSearch = event.target.value;
    renderGameView();
    const input = document.getElementById("offerSearch");
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
  });

  document.getElementById("offerSort").addEventListener("change", event => {
    state.gameSort = event.target.value;
    renderGameView();
  });

  attachOfferRowEvents();
}

function openOfferModal(offer) {
  state.selectedOffer = offer;

  document.getElementById("offerModalBody").innerHTML = `
    <div class="product-top">
      <div class="product-art">${offer.initials}</div>
      <div>
        <span class="eyebrow">${escapeHtml(offer.game)} · ${offer.category}</span>
        <h2 id="offerModalTitle">${escapeHtml(offer.title)}</h2>
        <div class="muted">Sold by <b style="color:var(--text)">${escapeHtml(offer.seller)}</b> · ★ ${offer.rating.toFixed(2)} · ${offer.sales} sales</div>
      </div>
    </div>

    <div class="detail-grid">
      <div class="detail-box"><span>Subcategory</span><b>${escapeHtml(offer.subcategory)}</b></div>
      <div class="detail-box"><span>Delivery</span><b>${escapeHtml(offer.delivery)}</b></div>
      <div class="detail-box"><span>Protection</span><b>Demo protected</b></div>
    </div>

    <div class="description">${escapeHtml(offer.description)}</div>

    <div class="action-bar">
      <div>
        <span class="muted">Total</span>
        <div class="big-price">${money(offer.price)}</div>
      </div>
      <div class="action-buttons">
        <button class="secondary-button" id="messageSellerButton" type="button">Message seller</button>
        <button class="primary-button" id="buyButton" type="button">Buy now — demo</button>
      </div>
    </div>
  `;

  openModal("offerModal");

  document.getElementById("messageSellerButton").addEventListener("click", () => {
    const chat = ensureConversation(offer, false);
    closeModal("offerModal");
    state.selectedChatKey = chat.key;
    activateView("chats");
  });

  document.getElementById("buyButton").addEventListener("click", () => {
    closeModal("offerModal");
    openCheckout(offer);
  });
}

function openCheckout(offer) {
  state.selectedOffer = offer;
  document.getElementById("checkoutModalBody").innerHTML = `
    <div class="notice">Demo only. No card is charged, no money moves, and no real item is transferred.</div>
    <h2 style="font:800 30px 'Bricolage Grotesque';margin:20px 0 8px">Review your order</h2>
    <div class="summary-row"><span>${escapeHtml(offer.title)}</span><b>${money(offer.price)}</b></div>
    <div class="summary-row"><span>Seller</span><b>${escapeHtml(offer.seller)}</b></div>
    <div class="summary-row"><span>Delivery</span><b>${escapeHtml(offer.delivery)}</b></div>
    <div class="summary-row"><span>Buyer protection</span><b>Included</b></div>
    <div class="summary-row"><span>Payment method</span><b>Demo balance</b></div>
    <div class="action-bar">
      <button class="secondary-button" data-close-modal="checkoutModal" type="button">Cancel</button>
      <button class="primary-button" id="completePurchaseButton" type="button">Complete demo purchase</button>
    </div>
  `;

  bindModalCloseButtons();
  openModal("checkoutModal");

  document.getElementById("completePurchaseButton").addEventListener("click", () => {
    const chat = ensureConversation(offer, true);
    state.selectedChatKey = chat.key;

    document.getElementById("checkoutModalBody").innerHTML = `
      <div class="success">
        <div class="success-icon">✓</div>
        <h2>Payment confirmed</h2>
        <p>Nexora automatically created or upgraded the shared chat between buyer and seller and posted a payment confirmation message there.</p>
        <button class="primary-button" id="openOrderChatButton" type="button">Open order chat</button>
      </div>
    `;

    document.getElementById("openOrderChatButton").addEventListener("click", () => {
      closeModal("checkoutModal");
      activateView("chats");
    });
  });
}

function conversationKey(offer) {
  return [offer.seller, offer.game, offer.id].join("|");
}

function ensureConversation(offer, paid) {
  const key = conversationKey(offer);
  let chat = conversations.find(item => item.key === key);

  if (!chat) {
    chat = {
      key,
      seller: offer.seller,
      buyer: "DemoBuyer",
      offerId: offer.id,
      game: offer.game,
      title: offer.title,
      price: offer.price,
      initials: offer.initials,
      orderId: null,
      status: "PRE_SALE",
      messages: []
    };
    conversations.unshift(chat);
    chat.messages.push({
      sender: "system",
      text: "Conversation started about “" + offer.title + "”. No payment has been made yet.",
      time: nowTime()
    });
  }

  if (paid && chat.status === "PRE_SALE") {
    chat.status = "PAID";
    chat.orderId = "NX-" + Math.floor(100000 + Math.random() * 900000);
    chat.messages.push({
      sender: "system",
      text:
        "Payment confirmed. DemoBuyer paid " + money(offer.price) + " for “" + offer.title +
        "”. Order " + chat.orderId + " is active. " + offer.seller +
        " can now deliver the purchase in this chat.",
      time: nowTime()
    });
  }

  saveChats();
  return chat;
}

function renderChats() {
  updateChatBadge();

  const roleLabel = state.role === "buyer" ? "buyer" : "seller";
  const selected = conversations.find(chat => chat.key === state.selectedChatKey) || conversations[0] || null;
  if (selected) state.selectedChatKey = selected.key;

  views.chats.innerHTML = `
    <div class="shell page-wrap">
      <div class="page-title">
        <span class="eyebrow">DEALS HAPPEN HERE</span>
        <h1>Chats</h1>
        <p>Pre-sale questions and paid orders live in the same conversation. After payment, Nexora posts the confirmation and order status automatically.</p>
      </div>

      <section class="chat-layout">
        <aside class="thread-panel">
          <div class="thread-panel-head">
            <strong>Conversations</strong>
            <span>Viewing as ${roleLabel}</span>
          </div>
          <div class="thread-list">
            ${conversations.length ? conversations.map(chat => renderThread(chat, selected)).join("") : '<div class="empty-state"><div><strong>No conversations yet</strong><p>Open an offer and message a seller.</p></div></div>'}
          </div>
        </aside>
        <section class="chat-pane" id="chatPane">
          ${selected ? renderChatPane(selected) : '<div class="empty-state"><div><strong>No chat selected</strong><p>Complete a demo purchase or message a seller first.</p></div></div>'}
        </section>
      </section>
    </div>
  `;

  document.querySelectorAll("[data-chat-key]").forEach(button => {
    button.addEventListener("click", () => {
      state.selectedChatKey = decodeURIComponent(button.dataset.chatKey);
      renderChats();
    });
  });

  if (selected) bindChatPane(selected);
}

function renderThread(chat, selected) {
  const counterpart = state.role === "buyer" ? chat.seller : chat.buyer;
  const lastMessage = chat.messages[chat.messages.length - 1];
  return `
    <button class="thread ${selected && selected.key === chat.key ? "active" : ""}" data-chat-key="${encodeURIComponent(chat.key)}" type="button">
      <span class="avatar">${escapeHtml(counterpart.slice(0,1).toUpperCase())}</span>
      <span class="thread-copy">
        <strong>${escapeHtml(counterpart)}</strong>
        <span>${escapeHtml(chat.game)} · ${escapeHtml(lastMessage ? lastMessage.text : chat.title)}</span>
      </span>
      <span class="thread-time">${lastMessage ? escapeHtml(lastMessage.time) : ""}</span>
    </button>
  `;
}

function renderChatPane(chat) {
  const counterpart = state.role === "buyer" ? chat.seller : chat.buyer;
  const self = state.role === "buyer" ? chat.buyer : chat.seller;
  const paid = chat.status !== "PRE_SALE";

  return `
    <div class="chat-header">
      <div class="chat-person">
        <span class="avatar">${escapeHtml(counterpart.slice(0,1).toUpperCase())}</span>
        <div>
          <strong>${escapeHtml(counterpart)}</strong>
          <span>${escapeHtml(chat.game)} · ${escapeHtml(chat.title)}</span>
        </div>
      </div>
      <span class="status-chip ${paid ? "paid" : ""}">${formatStatus(chat.status)}</span>
    </div>

    ${paid ? `
      <div class="order-strip">
        <div><b>${escapeHtml(chat.orderId || "")}</b> · ${escapeHtml(chat.title)}</div>
        <div><span>Total </span><b>${money(chat.price)}</b></div>
      </div>
      ${renderOrderProgress(chat)}
      <div class="order-actions">
        ${renderOrderActions(chat)}
      </div>
    ` : ""}

    <div class="messages" id="messages">
      ${chat.messages.map(message => renderMessage(message, self)).join("")}
    </div>

    <form class="chat-form" id="chatForm">
      <input id="chatInput" placeholder="Message ${escapeAttr(counterpart)}…" autocomplete="off">
      <button class="primary-button" type="submit">Send</button>
    </form>
  `;
}

function renderMessage(message, self) {
  if (message.sender === "system") {
    return `<div class="message system">${escapeHtml(message.text)}<small>${escapeHtml(message.time)}</small></div>`;
  }

  const mine = message.sender === self;
  return `<div class="message ${mine ? "mine" : ""}">${escapeHtml(message.text)}<small>${escapeHtml(message.time)}</small></div>`;
}

function renderOrderProgress(chat) {
  const rank = { PAID: 1, DELIVERED: 2, COMPLETED: 3, DISPUTED: 1 };
  const current = rank[chat.status] || 0;
  const steps = [
    { label: "Paid", value: 1 },
    { label: "Delivered", value: 2 },
    { label: "Completed", value: 3 }
  ];

  return '<div class="order-progress">' + steps.map(step => {
    const done = current > step.value || chat.status === "COMPLETED" || (chat.status === "DELIVERED" && step.value === 1);
    const isCurrent = current === step.value && chat.status !== "COMPLETED";
    return '<div class="order-step ' + (done ? "done " : "") + (isCurrent ? "current" : "") + '">' + step.label + '</div>';
  }).join("") + '</div>';
}

function renderOrderActions(chat) {
  const actions = [];

  if (state.role === "seller" && chat.status === "PAID") {
    actions.push('<button class="primary-button" id="markDeliveredButton" type="button">Mark as delivered</button>');
  }

  if (state.role === "buyer" && chat.status === "DELIVERED") {
    actions.push('<button class="primary-button" id="confirmReceivedButton" type="button">Confirm received</button>');
  }

  if (state.role === "buyer" && (chat.status === "PAID" || chat.status === "DELIVERED")) {
    actions.push('<button class="danger-button" id="openDisputeButton" type="button">Open dispute</button>');
  }

  if (chat.status === "COMPLETED") {
    actions.push('<span class="status-chip paid">Transaction completed</span>');
  }

  if (chat.status === "DISPUTED") {
    actions.push('<span class="status-chip">Dispute opened · support review</span>');
  }

  return actions.join("");
}

function bindChatPane(chat) {
  const messages = document.getElementById("messages");
  if (messages) messages.scrollTop = messages.scrollHeight;

  const form = document.getElementById("chatForm");
  if (form) {
    form.addEventListener("submit", event => {
      event.preventDefault();
      const input = document.getElementById("chatInput");
      const value = input.value.trim();
      if (!value) return;

      const sender = state.role === "buyer" ? chat.buyer : chat.seller;
      chat.messages.push({ sender, text: value, time: nowTime() });
      saveChats();
      renderChats();
    });
  }

  const deliveredButton = document.getElementById("markDeliveredButton");
  if (deliveredButton) {
    deliveredButton.addEventListener("click", () => {
      chat.status = "DELIVERED";
      chat.messages.push({
        sender: "system",
        text: chat.seller + " marked the order as delivered. DemoBuyer can now review the delivery and confirm receipt.",
        time: nowTime()
      });
      saveChats();
      renderChats();
    });
  }

  const confirmButton = document.getElementById("confirmReceivedButton");
  if (confirmButton) {
    confirmButton.addEventListener("click", () => {
      chat.status = "COMPLETED";
      chat.messages.push({
        sender: "system",
        text: "DemoBuyer confirmed the delivery. The demo transaction is now completed.",
        time: nowTime()
      });
      saveChats();
      renderChats();
    });
  }

  const disputeButton = document.getElementById("openDisputeButton");
  if (disputeButton) {
    disputeButton.addEventListener("click", () => {
      chat.status = "DISPUTED";
      chat.messages.push({
        sender: "system",
        text: "A dispute was opened. In the real marketplace, both sides would keep communicating here while support reviews the order.",
        time: nowTime()
      });
      saveChats();
      renderChats();
    });
  }
}

function formatStatus(status) {
  const labels = {
    PRE_SALE: "Pre-sale chat",
    PAID: "Paid",
    DELIVERED: "Delivered",
    COMPLETED: "Completed",
    DISPUTED: "Disputed"
  };
  return labels[status] || status;
}

function renderSettings() {
  views.settings.innerHTML = `
    <div class="shell page-wrap">
      <div class="page-title">
        <span class="eyebrow">ACCOUNT & EXPERIENCE</span>
        <h1>Settings</h1>
        <p>Switch perspectives to test the same transaction as buyer and seller, and configure prototype notifications.</p>
      </div>

      <section class="settings-grid">
        <article class="setting-card">
          <h3>Demo perspective</h3>
          <p>Buyer and seller see the exact same shared order chat from different sides.</p>
          <div class="role-switch">
            <button class="${state.role === "buyer" ? "active" : ""}" id="roleBuyer" type="button">Buyer</button>
            <button class="${state.role === "seller" ? "active" : ""}" id="roleSeller" type="button">Seller</button>
          </div>
        </article>

        <article class="setting-card">
          <h3>Transaction notifications</h3>
          <p>Prototype preferences for important deal events.</p>
          ${renderToggle("messages","New messages")}
          ${renderToggle("payments","Payment confirmed")}
          ${renderToggle("delivery","Delivery updates")}
        </article>

        <article class="setting-card">
          <h3>Core transaction rule</h3>
          <p>Every paid order creates or upgrades a shared conversation between buyer and seller.</p>
          <div class="notice">Payment → shared order chat → delivery → buyer confirmation → completed.</div>
        </article>

        <article class="setting-card">
          <h3>Demo data</h3>
          <p>Chats and order statuses are stored only in this browser for the prototype.</p>
          <button class="danger-button" id="resetDemoButton" type="button">Reset chats & demo orders</button>
        </article>
      </section>
    </div>
  `;

  document.getElementById("roleBuyer").addEventListener("click", () => setRole("buyer"));
  document.getElementById("roleSeller").addEventListener("click", () => setRole("seller"));

  document.querySelectorAll("[data-setting-key]").forEach(button => {
    button.addEventListener("click", () => {
      const key = button.dataset.settingKey;
      state.settings[key] = !state.settings[key];
      saveSettings();
      renderSettings();
    });
  });

  document.getElementById("resetDemoButton").addEventListener("click", () => {
    conversations = [];
    state.selectedChatKey = null;
    saveChats();
    renderSettings();
  });
}

function renderToggle(key, label) {
  return `
    <div class="toggle-row">
      <span>${label}</span>
      <button class="toggle ${state.settings[key] ? "on" : ""}" data-setting-key="${key}" type="button" aria-label="Toggle ${label}">
        <span></span>
      </button>
    </div>
  `;
}

function setRole(role) {
  state.role = role;
  localStorage.setItem("nexora-demo-role", role);
  renderSettings();
}

function openModal(id) {
  const modal = document.getElementById(id);
  modal.classList.add("open");
  modal.setAttribute("aria-hidden", "false");
  bindModalCloseButtons();
}

function closeModal(id) {
  const modal = document.getElementById(id);
  modal.classList.remove("open");
  modal.setAttribute("aria-hidden", "true");
}

function bindModalCloseButtons() {
  document.querySelectorAll("[data-close-modal]").forEach(button => {
    button.onclick = () => closeModal(button.dataset.closeModal);
  });
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&","&amp;")
    .replaceAll("<","&lt;")
    .replaceAll(">","&gt;")
    .replaceAll('"',"&quot;")
    .replaceAll("'","&#039;");
}

function escapeAttr(value) {
  return escapeHtml(value);
}

document.getElementById("brandButton").addEventListener("click", () => activateView("marketplace"));
navButtons.marketplace.addEventListener("click", () => activateView("marketplace"));
navButtons.chats.addEventListener("click", () => activateView("chats"));
navButtons.settings.addEventListener("click", () => activateView("settings"));

document.querySelectorAll(".overlay").forEach(overlay => {
  overlay.addEventListener("click", event => {
    if (event.target === overlay) closeModal(overlay.id);
  });
});

document.addEventListener("keydown", event => {
  if (event.key === "Escape") {
    document.querySelectorAll(".overlay.open").forEach(overlay => closeModal(overlay.id));
  }
});

updateChatBadge();
activateView("marketplace");