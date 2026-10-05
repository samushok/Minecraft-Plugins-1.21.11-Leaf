import express from "express";
import bcrypt from "bcryptjs";
import crypto from "crypto";
import { createClient } from "redis";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const app = express();
const PORT = process.env.PORT || 10000;
const REDIS_URL = process.env.REDIS_URL;
const NOVA_PASSWORD = process.env.NOVA_BOOTSTRAP_PASSWORD;

if (!REDIS_URL) throw new Error("REDIS_URL is required");
if (!NOVA_PASSWORD) throw new Error("NOVA_BOOTSTRAP_PASSWORD is required");

const redis = createClient({ url: REDIS_URL });
redis.on("error", (err) => console.error("Redis error", err));
await redis.connect();

app.disable("x-powered-by");
app.use((req,res,next)=>{
  const origin=req.headers.origin;
  const allowed=new Set(["https://nexora-roblox-preview.onrender.com","https://novavault-live.onrender.com"]);
  if(origin&&allowed.has(origin)){
    res.setHeader("Access-Control-Allow-Origin",origin);
    res.setHeader("Vary","Origin");
    res.setHeader("Access-Control-Allow-Headers","Content-Type, Authorization");
    res.setHeader("Access-Control-Allow-Methods","GET,POST,OPTIONS");
  }
  if(req.method==="OPTIONS") return res.sendStatus(204);
  next();
});
app.use(express.json({ limit: "256kb" }));

const staticDir = path.resolve(__dirname, "../render-preview");

const key = {
  user: id => `user:${id}`,
  userByUsername: username => `userByUsername:${username.toLowerCase()}`,
  userByEmail: email => `userByEmail:${email.toLowerCase()}`,
  session: hash => `session:${hash}`,
  product: id => `product:${id}`,
  chat: id => `chat:${id}`,
  messages: chatId => `messages:${chatId}`,
};

function sessionHash(token) {
  return crypto.createHash("sha256").update(token).digest("hex");
}

function publicUser(user) {
  return {
    id: user.id,
    username: user.username,
    email: user.email,
    displayName: user.displayName,
    roles: ["buyer", ...(user.isSeller ? ["seller"] : []), ...(user.isAdmin ? ["admin"] : [])],
    sellerApproved: Boolean(user.isSeller),
    sellerName: user.sellerName || null,
    verified: Boolean(user.verified),
  };
}

async function nextId(name) {
  return String(await redis.incr(`seq:${name}`));
}

async function saveUser(user) {
  await redis.set(key.user(user.id), JSON.stringify(user));
  await redis.set(key.userByUsername(user.username), user.id);
  await redis.set(key.userByEmail(user.email), user.id);
  await redis.sAdd("users", user.id);
}

async function getUserById(id) {
  if (!id) return null;
  const raw = await redis.get(key.user(String(id)));
  return raw ? JSON.parse(raw) : null;
}

async function findUser(identifier) {
  const normalized = String(identifier || "").trim().toLowerCase();
  let id = await redis.get(key.userByUsername(normalized));
  if (!id) id = await redis.get(key.userByEmail(normalized));
  return getUserById(id);
}

function bearer(req) {
  const value = req.headers.authorization || "";
  return value.startsWith("Bearer ") ? value.slice(7).trim() : null;
}

async function createSession(userId) {
  const token = crypto.randomBytes(32).toString("base64url");
  const hash = sessionHash(token);
  await redis.set(key.session(hash), userId, { EX: 30 * 24 * 60 * 60 });
  return token;
}

async function getSessionUser(req) {
  const token = bearer(req);
  if (!token) return null;
  const userId = await redis.get(key.session(sessionHash(token)));
  return getUserById(userId);
}

function requireAuth(handler) {
  return async (req, res) => {
    try {
      const user = await getSessionUser(req);
      if (!user) return res.status(401).json({ error: "Authentication required" });
      req.user = user;
      await handler(req, res);
    } catch (error) {
      console.error(error);
      if (!res.headersSent) res.status(500).json({ error: "Server error" });
    }
  };
}

function requireSeller(handler) {
  return requireAuth(async (req, res) => {
    if (!req.user.isSeller) return res.status(403).json({ error: "Seller access required" });
    await handler(req, res);
  });
}

function requireAdmin(handler) {
  return requireAuth(async (req, res) => {
    if (!req.user.isAdmin) return res.status(403).json({ error: "Admin access required" });
    await handler(req, res);
  });
}

async function saveProduct(product) {
  await redis.set(key.product(product.id), JSON.stringify(product));
  await redis.sAdd("products", product.id);
}

async function getProduct(id) {
  const raw = await redis.get(key.product(String(id)));
  return raw ? JSON.parse(raw) : null;
}

async function listProducts() {
  const ids = await redis.sMembers("products");
  const rows = await Promise.all(ids.map(getProduct));
  return rows.filter(Boolean).filter(p => p.active && p.stock > 0).sort((a,b) => a.createdAt - b.createdAt);
}

async function saveChat(chat) {
  chat.updatedAt = Date.now();
  await redis.set(key.chat(chat.id), JSON.stringify(chat));
  await redis.sAdd("chats", chat.id);
  await redis.sAdd(`chats:user:${chat.buyerId}`, chat.id);
  await redis.sAdd(`chats:user:${chat.sellerId}`, chat.id);
  await redis.set(`chatByBuyerProduct:${chat.buyerId}:${chat.productId}`, chat.id);
}

async function getChat(id) {
  const raw = await redis.get(key.chat(String(id)));
  return raw ? JSON.parse(raw) : null;
}

async function addMessage(chatId, message) {
  await redis.rPush(key.messages(chatId), JSON.stringify({ id: crypto.randomUUID(), createdAt: Date.now(), ...message }));
}

async function listMessages(chatId) {
  const rows = await redis.lRange(key.messages(chatId), 0, -1);
  return rows.map(JSON.parse);
}

async function bootstrap() {
  let nova = await findUser("Nova");
  if (!nova) {
    const passwordHash = await bcrypt.hash(NOVA_PASSWORD, 12);
    nova = {
      id: await nextId("user"),
      username: "Nova",
      email: "nova@novavault.demo",
      displayName: "Nova",
      passwordHash,
      isSeller: true,
      isAdmin: true,
      sellerName: "NovaOfficial",
      verified: true,
      createdAt: Date.now(),
    };
    await saveUser(nova);
  } else {
    nova.isSeller = true;
    nova.isAdmin = true;
    nova.sellerName = "NovaOfficial";
    nova.verified = true;
    await saveUser(nova);
  }

  const existingProducts = await redis.sCard("products");
  if (!existingProducts) {
    const seed = [
      ["Robux Spent","10,000+ Robux Spent Account","10K+ SPENT","2019–2024","Mixed","10K+ R$ spent",1299,24,"10K","Demo stock with more than 10,000 Robux spent historically."],
      ["Robux Spent","25,000+ Robux Spent Account","25K+ SPENT","2018–2024","Mixed+","25K+ R$ spent",2199,13,"25K","Higher-spend demo stock with at least 25,000 Robux spent historically."],
      ["Robux Spent","50,000+ Robux Spent Account","50K+ SPENT","2017–2023","Premium","50K+ R$ spent",3899,7,"50K","Premium demo stock with 50,000+ Robux total spend history."],
      ["Robux Spent","100,000+ Robux Spent Account","100K+ SPENT","2016–2023","High value","100K+ R$ spent",6999,3,"100K","Top-tier demo stock based on historical Robux spend."],
      ["Gamepasses","Blox Fruits Gamepass Account","BLOX FRUITS","2019–2024","Gamepasses","Premium passes",2699,11,"BF","Demo Blox Fruits stock with paid gamepasses."],
      ["Gamepasses","Blox Fruits Premium Account","PREMIUM BF","2018–2023","Passes + extras","Premium setup",4499,5,"BF+","Higher-tier Blox Fruits demo stock."],
      ["Robux Balance","Account With Robux Balance","ROBUX BALANCE","2020–2024","Balance + items","Varies by stock",1799,16,"R$","Demo account category with a stated Robux balance."],
      ["Robux Balance","High Robux Balance Account","HIGH BALANCE","2018–2024","Higher balance","Premium stock",3499,6,"R$+","Higher-value demo balance stock."],
      ["Game Inventory","Murder Mystery 2 Inventory Account","MM2 INVENTORY","2018–2024","MM2 items","Godlies / sets",2399,9,"MM2","Demo MM2 inventory stock."],
      ["Game Inventory","Adopt Me Inventory Account","ADOPT ME","2018–2024","Pets / items","Mixed inventory",2899,8,"AM","Demo Adopt Me account stock."],
      ["Gamepasses","Premium Gamepasses Account","MULTI-GAME","2017–2023","Multiple passes","Cross-game",3299,10,"GP","Demo multi-game account category."],
      ["Aged + Spent","2018–2020 Account · 10,000+ Robux Spent","AGED + SPENT","2018–2020","Mixed","10K+ R$ spent",2499,12,"18+","Demo stock combining older age with a minimum spend threshold."]
    ];
    for (const row of seed) {
      const [type,title,tag,ageLabel,inventoryLabel,valueLabel,priceCents,stock,symbol,description] = row;
      await saveProduct({
        id: await nextId("product"),
        sellerId: nova.id,
        sellerName: "NovaOfficial",
        sellerVerified: true,
        type,title,tag,ageLabel,
        transferLabel: "Transfer-ready",
        inventoryLabel,valueLabel,priceCents,stock,symbol,description,
        active: true,
        createdAt: Date.now(),
      });
    }
  }
}

app.get("/api/health", (_req,res) => res.json({ ok:true, storage:"server" }));

app.post("/api/auth/register", async (req,res) => {
  try {
    const username = String(req.body.username || "").trim();
    const email = String(req.body.email || "").trim().toLowerCase();
    const password = String(req.body.password || "");
    if (!/^[A-Za-z0-9_]{3,24}$/.test(username)) return res.status(400).json({ error:"Username must be 3–24 letters, numbers, or underscores" });
    if (!/^\S+@\S+\.\S+$/.test(email)) return res.status(400).json({ error:"Enter a valid email" });
    if (password.length < 8) return res.status(400).json({ error:"Password must be at least 8 characters" });
    if (await findUser(username) || await findUser(email)) return res.status(409).json({ error:"Username or email already exists" });
    const user = {
      id: await nextId("user"),
      username,email,displayName:username,
      passwordHash: await bcrypt.hash(password,12),
      isSeller:false,isAdmin:false,sellerName:null,verified:false,createdAt:Date.now()
    };
    await saveUser(user);
    const token = await createSession(user.id);
    res.status(201).json({ token, user: publicUser(user) });
  } catch (error) {
    console.error(error);
    res.status(500).json({ error:"Could not create account" });
  }
});

app.post("/api/auth/login", async (req,res) => {
  const user = await findUser(req.body.identifier);
  const password = String(req.body.password || "");
  if (!user || !(await bcrypt.compare(password,user.passwordHash))) return res.status(401).json({ error:"Invalid username/email or password" });
  const token = await createSession(user.id);
  res.json({ token, user: publicUser(user) });
});

app.post("/api/auth/logout", requireAuth(async (req,res) => {
  const token = bearer(req);
  if (token) await redis.del(key.session(sessionHash(token)));
  res.json({ ok:true });
}));

app.get("/api/me", async (req,res) => {
  const user = await getSessionUser(req);
  res.json({ user:user ? publicUser(user) : null });
});

app.get("/api/products", async (_req,res) => {
  res.json({ products: await listProducts() });
});

app.post("/api/products", requireSeller(async (req,res) => {
  const title=String(req.body.title||"").trim();
  const type=String(req.body.type||"").trim();
  const valueLabel=String(req.body.valueLabel||"").trim();
  const priceCents=Number(req.body.priceCents);
  const stock=Number(req.body.stock);
  if(title.length<4||valueLabel.length<2||!Number.isInteger(priceCents)||priceCents<100||!Number.isInteger(stock)||stock<1) return res.status(400).json({ error:"Check listing fields" });
  const product={
    id:await nextId("product"),sellerId:req.user.id,sellerName:req.user.sellerName||req.user.displayName,sellerVerified:Boolean(req.user.verified),
    type,title,tag:req.user.sellerName==="NovaOfficial"?"NOVAVAULT OFFICIAL":"SELLER",
    ageLabel:"Varies",transferLabel:"Transfer-ready",inventoryLabel:"As listed",valueLabel,priceCents,stock,symbol:req.user.sellerName==="NovaOfficial"?"NV":"S",
    description:req.user.sellerName==="NovaOfficial"?"Official NovaVault demo listing published by NovaOfficial.":"Seller demo listing.",
    active:true,createdAt:Date.now()
  };
  await saveProduct(product);
  res.status(201).json({ product });
}));

app.get("/api/admin/users", requireAdmin(async (_req,res) => {
  const ids=await redis.sMembers("users");
  const users=(await Promise.all(ids.map(getUserById))).filter(Boolean).map(publicUser);
  res.json({ users });
}));

app.post("/api/chats", requireAuth(async (req,res) => {
  const product=await getProduct(req.body.productId);
  if(!product||!product.active) return res.status(404).json({ error:"Product not found" });
  if(String(product.sellerId)===String(req.user.id)) return res.status(400).json({ error:"You cannot message your own listing" });
  let chatId=await redis.get(`chatByBuyerProduct:${req.user.id}:${product.id}`);
  let chat=chatId?await getChat(chatId):null;
  if(!chat){
    chat={id:await nextId("chat"),buyerId:req.user.id,buyerName:req.user.displayName,sellerId:product.sellerId,sellerName:product.sellerName,productId:product.id,productTitle:product.title,priceCents:product.priceCents,symbol:product.symbol,status:"PRE_SALE",orderCode:null,createdAt:Date.now(),updatedAt:Date.now()};
    await saveChat(chat);
    await addMessage(chat.id,{senderId:null,senderKind:"system",senderName:"NovaVault",body:`Conversation started about “${product.title}”. No payment has been made yet.`});
  }
  res.status(201).json({ chatId:chat.id });
}));

app.post("/api/orders/buy", requireAuth(async (req,res) => {
  const product=await getProduct(req.body.productId);
  if(!product||!product.active||product.stock<1) return res.status(409).json({ error:"Product is out of stock" });
  if(String(product.sellerId)===String(req.user.id)) return res.status(400).json({ error:"You cannot buy your own listing" });

  let chatId=await redis.get(`chatByBuyerProduct:${req.user.id}:${product.id}`);
  let chat=chatId?await getChat(chatId):null;
  if(!chat){
    chat={id:await nextId("chat"),buyerId:req.user.id,buyerName:req.user.displayName,sellerId:product.sellerId,sellerName:product.sellerName,productId:product.id,productTitle:product.title,priceCents:product.priceCents,symbol:product.symbol,status:"PRE_SALE",orderCode:null,createdAt:Date.now(),updatedAt:Date.now()};
    await saveChat(chat);
    await addMessage(chat.id,{senderId:null,senderKind:"system",senderName:"NovaVault",body:`Conversation started about “${product.title}”.`});
  }
  if(chat.status==="PRE_SALE"){
    chat.status="PAID";
    chat.orderCode="NX-"+crypto.randomInt(100000,999999);
    product.stock-=1;
    await saveProduct(product);
    await saveChat(chat);
    await addMessage(chat.id,{senderId:null,senderKind:"system",senderName:"NovaVault",body:`Payment confirmed. ${req.user.displayName} paid ${(product.priceCents/100).toFixed(2)} USD for “${product.title}”. Order ${chat.orderCode} is active. ${product.sellerName} can now coordinate delivery in this chat.`});
  }
  res.json({ chatId:chat.id });
}));

app.get("/api/chats", requireAuth(async (req,res) => {
  const ids=await redis.sMembers(`chats:user:${req.user.id}`);
  const rows=(await Promise.all(ids.map(getChat))).filter(Boolean).sort((a,b)=>b.updatedAt-a.updatedAt);
  const data=[];
  for(const chat of rows) data.push({ ...chat, messages:await listMessages(chat.id) });
  res.json({ chats:data });
}));

app.post("/api/chats/:id/messages", requireAuth(async (req,res) => {
  const chat=await getChat(req.params.id);
  if(!chat||![String(chat.buyerId),String(chat.sellerId)].includes(String(req.user.id))) return res.status(404).json({ error:"Chat not found" });
  const body=String(req.body.body||"").trim();
  if(!body||body.length>2000) return res.status(400).json({ error:"Message must be 1–2000 characters" });
  await addMessage(chat.id,{senderId:req.user.id,senderKind:"user",senderName:req.user.displayName,body});
  await saveChat(chat);
  res.status(201).json({ ok:true });
}));

app.post("/api/chats/:id/status", requireAuth(async (req,res) => {
  const chat=await getChat(req.params.id);
  if(!chat||![String(chat.buyerId),String(chat.sellerId)].includes(String(req.user.id))) return res.status(404).json({ error:"Chat not found" });
  const action=String(req.body.action||"");
  let next=null,body=null;
  if(action==="deliver"&&String(chat.sellerId)===String(req.user.id)&&chat.status==="PAID"){next="DELIVERED";body=`${chat.sellerName} marked the order as delivered. ${chat.buyerName} can review and confirm receipt.`;}
  else if(action==="confirm"&&String(chat.buyerId)===String(req.user.id)&&chat.status==="DELIVERED"){next="COMPLETED";body=`${chat.buyerName} confirmed receipt. The transaction is completed.`;}
  else if(action==="dispute"&&String(chat.buyerId)===String(req.user.id)&&["PAID","DELIVERED"].includes(chat.status)){next="DISPUTED";body="A dispute was opened. NovaVault support can review the order conversation and submitted evidence.";}
  else return res.status(400).json({ error:"Action is not allowed for this order" });
  chat.status=next;
  await saveChat(chat);
  await addMessage(chat.id,{senderId:null,senderKind:"system",senderName:"NovaVault",body});
  res.json({ status:next });
}));

app.use(express.static(staticDir));
app.get("*", (_req,res)=>res.sendFile(path.join(staticDir,"index.html")));

await bootstrap();
app.listen(PORT,"0.0.0.0",()=>console.log(`NovaVault server listening on ${PORT}`));
