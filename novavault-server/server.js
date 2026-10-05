import express from "express";
import pg from "pg";
import crypto from "crypto";
import path from "path";
import { fileURLToPath } from "url";

const { Pool } = pg;
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const app = express();
const PORT = process.env.PORT || 10000;
const DATABASE_URL = process.env.DATABASE_URL;

const pool = DATABASE_URL ? new Pool({
  connectionString: DATABASE_URL,
  ssl: process.env.NODE_ENV === "production" ? { rejectUnauthorized: false } : false,
}) : null;

app.use("/api", (req,res,next)=>{
  if(req.path==="/health") return next();
  if(!pool) return res.status(503).json({error:"Database connection is not configured yet"});
  next();
});

app.disable("x-powered-by");
app.use(express.json({ limit: "256kb" }));
app.use((req,res,next)=>{
  const allowed="https://nexora-roblox-preview.onrender.com";
  const origin=req.headers.origin;
  if(origin===allowed) res.setHeader("Access-Control-Allow-Origin",allowed);
  res.setHeader("Vary","Origin");
  res.setHeader("Access-Control-Allow-Headers","Content-Type, Authorization");
  res.setHeader("Access-Control-Allow-Methods","GET,POST,OPTIONS");
  if(req.method==="OPTIONS") return res.sendStatus(204);
  next();
});

const staticDir = path.resolve(__dirname, "../render-preview");

function publicUser(row) {
  return {
    id: row.id,
    username: row.username,
    email: row.email,
    displayName: row.display_name,
    roles: ["buyer", ...(row.is_seller ? ["seller"] : []), ...(row.is_admin ? ["admin"] : [])],
    sellerApproved: row.is_seller,
    sellerName: row.seller_name || null,
    verified: row.verified,
  };
}

const NOVA_BOOTSTRAP_HASH = "scrypt$16384$8$1$EcZdWsbk61JL6QELlGmlFQ$cp7bTSqQwu5dbHBHVRY7LnmC_rpfliixRcVN3q7gYZIyk0WWeTgzzT3r87GKUdVbjtZeQp1nfBwUJHPf4p_iSA";

function sessionHash(token) {
  return crypto.createHash("sha256").update(token).digest("hex");
}

function b64url(buffer) {
  return Buffer.from(buffer).toString("base64url");
}

function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const n = 16384, r = 8, p = 1;
  const key = crypto.scryptSync(password, salt, 64, { N: n, r, p });
  return ["scrypt", n, r, p, b64url(salt), b64url(key)].join("$");
}

function verifyPassword(password, encoded) {
  try {
    const [kind,nText,rText,pText,saltText,keyText] = String(encoded).split("$");
    if (kind !== "scrypt") return false;
    const key = Buffer.from(keyText, "base64url");
    const derived = crypto.scryptSync(
      password,
      Buffer.from(saltText, "base64url"),
      key.length,
      { N: Number(nText), r: Number(rText), p: Number(pText) },
    );
    return key.length === derived.length && crypto.timingSafeEqual(key, derived);
  } catch {
    return false;
  }
}

async function createSession(userId) {
  const token = crypto.randomBytes(32).toString("base64url");
  const hash = sessionHash(token);
  await pool.query(
    "INSERT INTO sessions (token_hash, user_id, expires_at) VALUES ($1,$2,NOW()+INTERVAL '30 days')",
    [hash, userId],
  );
  return token;
}

function cookieValue(req, name) {
  const raw = req.headers.cookie || "";
  for (const part of raw.split(";")) {
    const [key, ...rest] = part.trim().split("=");
    if (key === name) return decodeURIComponent(rest.join("="));
  }
  return null;
}

async function getSessionUser(req) {
  const auth = String(req.headers.authorization || "");
  const bearer = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  const token = bearer || cookieValue(req, "nv_session");
  if (!token) return null;
  const result = await pool.query(
    `SELECT u.*
     FROM sessions s
     JOIN users u ON u.id=s.user_id
     WHERE s.token_hash=$1 AND s.expires_at>NOW()`,
    [sessionHash(token)],
  );
  return result.rows[0] || null;
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
    if (!req.user.is_seller) return res.status(403).json({ error: "Seller access required" });
    await handler(req, res);
  });
}

function requireAdmin(handler) {
  return requireAuth(async (req, res) => {
    if (!req.user.is_admin) return res.status(403).json({ error: "Admin access required" });
    await handler(req, res);
  });
}

async function initDb() {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS users (
      id BIGSERIAL PRIMARY KEY,
      username VARCHAR(32) UNIQUE NOT NULL,
      email VARCHAR(160) UNIQUE NOT NULL,
      password_hash TEXT NOT NULL,
      display_name VARCHAR(48) NOT NULL,
      is_seller BOOLEAN NOT NULL DEFAULT FALSE,
      is_admin BOOLEAN NOT NULL DEFAULT FALSE,
      seller_name VARCHAR(64),
      verified BOOLEAN NOT NULL DEFAULT FALSE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS sessions (
      token_hash TEXT PRIMARY KEY,
      user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      expires_at TIMESTAMPTZ NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS products (
      id BIGSERIAL PRIMARY KEY,
      seller_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      slug VARCHAR(120) UNIQUE NOT NULL,
      type VARCHAR(60) NOT NULL,
      title VARCHAR(160) NOT NULL,
      tag VARCHAR(80) NOT NULL,
      age_label VARCHAR(80) NOT NULL,
      transfer_label VARCHAR(80) NOT NULL DEFAULT 'Transfer-ready',
      inventory_label VARCHAR(100) NOT NULL,
      value_label VARCHAR(100) NOT NULL,
      price_cents INTEGER NOT NULL CHECK(price_cents >= 0),
      stock INTEGER NOT NULL DEFAULT 0 CHECK(stock >= 0),
      symbol VARCHAR(12) NOT NULL,
      description TEXT NOT NULL,
      active BOOLEAN NOT NULL DEFAULT TRUE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS chats (
      id BIGSERIAL PRIMARY KEY,
      buyer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      seller_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
      status VARCHAR(20) NOT NULL DEFAULT 'PRE_SALE',
      order_code VARCHAR(30),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(buyer_id, product_id)
    );

    CREATE TABLE IF NOT EXISTS messages (
      id BIGSERIAL PRIMARY KEY,
      chat_id BIGINT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
      sender_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
      sender_kind VARCHAR(10) NOT NULL DEFAULT 'user',
      body TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
    CREATE INDEX IF NOT EXISTS idx_chats_buyer ON chats(buyer_id);
    CREATE INDEX IF NOT EXISTS idx_chats_seller ON chats(seller_id);
    CREATE INDEX IF NOT EXISTS idx_messages_chat ON messages(chat_id, created_at);
  `);

  const novaResult = await pool.query(
    `INSERT INTO users
       (username,email,password_hash,display_name,is_seller,is_admin,seller_name,verified)
     VALUES ('Nova','nova@novavault.demo',$1,'Nova',TRUE,TRUE,'NovaOfficial',TRUE)
     ON CONFLICT (username) DO UPDATE SET
       is_seller=TRUE,is_admin=TRUE,seller_name='NovaOfficial',verified=TRUE
     RETURNING id`,
    [NOVA_BOOTSTRAP_HASH],
  );
  const novaId = novaResult.rows[0].id;

  const count = await pool.query("SELECT COUNT(*)::int AS count FROM products");
  if (count.rows[0].count === 0) {
    const seed = [
      ["spent-10k","Robux Spent","10,000+ Robux Spent Account","10K+ SPENT","2019–2024","Mixed","10K+ R$ spent",1299,24,"10K","Demo stock with more than 10,000 Robux spent historically."],
      ["spent-25k","Robux Spent","25,000+ Robux Spent Account","25K+ SPENT","2018–2024","Mixed+","25K+ R$ spent",2199,13,"25K","Higher-spend demo stock with at least 25,000 Robux spent historically."],
      ["spent-50k","Robux Spent","50,000+ Robux Spent Account","50K+ SPENT","2017–2023","Premium","50K+ R$ spent",3899,7,"50K","Premium demo stock with 50,000+ Robux total spend history."],
      ["spent-100k","Robux Spent","100,000+ Robux Spent Account","100K+ SPENT","2016–2023","High value","100K+ R$ spent",6999,3,"100K","Top-tier demo stock based on historical Robux spend."],
      ["bf-gamepasses","Gamepasses","Blox Fruits Gamepass Account","BLOX FRUITS","2019–2024","Gamepasses","Premium passes",2699,11,"BF","Demo Blox Fruits stock with paid gamepasses."],
      ["bf-premium","Gamepasses","Blox Fruits Premium Account","PREMIUM BF","2018–2023","Passes + extras","Premium setup",4499,5,"BF+","Higher-tier Blox Fruits demo stock."],
      ["robux-balance","Robux Balance","Account With Robux Balance","ROBUX BALANCE","2020–2024","Balance + items","Varies by stock",1799,16,"R$","Demo account category with a stated Robux balance."],
      ["robux-balance-plus","Robux Balance","High Robux Balance Account","HIGH BALANCE","2018–2024","Higher balance","Premium stock",3499,6,"R$+","Higher-value demo balance stock."],
      ["mm2-inventory","Game Inventory","Murder Mystery 2 Inventory Account","MM2 INVENTORY","2018–2024","MM2 items","Godlies / sets",2399,9,"MM2","Demo MM2 inventory stock."],
      ["adopt-inventory","Game Inventory","Adopt Me Inventory Account","ADOPT ME","2018–2024","Pets / items","Mixed inventory",2899,8,"AM","Demo Adopt Me account stock."],
      ["premium-passes","Gamepasses","Premium Gamepasses Account","MULTI-GAME","2017–2023","Multiple passes","Cross-game",3299,10,"GP","Demo multi-game account category."],
      ["aged-spent","Aged + Spent","2018–2020 Account · 10,000+ Robux Spent","AGED + SPENT","2018–2020","Mixed","10K+ R$ spent",2499,12,"18+","Demo stock combining older age with a minimum spend threshold."]
    ];
    for (const p of seed) {
      await pool.query(
        `INSERT INTO products
         (seller_id,slug,type,title,tag,age_label,inventory_label,value_label,price_cents,stock,symbol,description)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)`,
        [novaId, ...p],
      );
    }
  }
}

app.get("/api/health", async (_req, res) => {
  if(!pool) return res.json({ok:false,database:false,setupRequired:true});
  const db = await pool.query("SELECT NOW() AS now");
  res.json({ ok: true, database:true, dbTime: db.rows[0].now });
});

app.post("/api/auth/register", async (req, res) => {
  try {
    const username = String(req.body.username || "").trim();
    const email = String(req.body.email || "").trim().toLowerCase();
    const password = String(req.body.password || "");
    if (!/^[A-Za-z0-9_]{3,24}$/.test(username)) return res.status(400).json({ error: "Username must be 3–24 letters, numbers, or underscores" });
    if (!/^\S+@\S+\.\S+$/.test(email)) return res.status(400).json({ error: "Enter a valid email" });
    if (password.length < 8) return res.status(400).json({ error: "Password must be at least 8 characters" });

    const passwordHash = hashPassword(password);
    const result = await pool.query(
      `INSERT INTO users (username,email,password_hash,display_name)
       VALUES ($1,$2,$3,$1) RETURNING *`,
      [username, email, passwordHash],
    );
    const token = await createSession(result.rows[0].id);
    res.status(201).json({ token, user: publicUser(result.rows[0]) });
  } catch (error) {
    if (error.code === "23505") return res.status(409).json({ error: "Username or email already exists" });
    console.error(error);
    res.status(500).json({ error: "Could not create account" });
  }
});

app.post("/api/auth/login", async (req, res) => {
  const identifier = String(req.body.identifier || "").trim();
  const password = String(req.body.password || "");
  const result = await pool.query(
    "SELECT * FROM users WHERE LOWER(username)=LOWER($1) OR LOWER(email)=LOWER($1) LIMIT 1",
    [identifier],
  );
  const user = result.rows[0];
  if (!user || !verifyPassword(password, user.password_hash)) {
    return res.status(401).json({ error: "Invalid username/email or password" });
  }
  const token = await createSession(user.id);
  res.json({ token, user: publicUser(user) });
});

app.post("/api/auth/logout", requireAuth(async (req, res) => {
  const auth = String(req.headers.authorization || "");
  const bearer = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  const token = bearer || cookieValue(req, "nv_session");
  if (token) await pool.query("DELETE FROM sessions WHERE token_hash=$1", [sessionHash(token)]);
  res.json({ ok: true });
}));

app.get("/api/me", async (req, res) => {
  const user = await getSessionUser(req);
  res.json({ user: user ? publicUser(user) : null });
});

app.get("/api/products", async (_req, res) => {
  const result = await pool.query(
    `SELECT p.*, u.seller_name, u.verified AS seller_verified
     FROM products p JOIN users u ON u.id=p.seller_id
     WHERE p.active=TRUE AND p.stock>0
     ORDER BY p.created_at ASC`,
  );
  res.json({ products: result.rows });
});

app.post("/api/products", requireSeller(async (req, res) => {
  const title = String(req.body.title || "").trim();
  const type = String(req.body.type || "").trim();
  const valueLabel = String(req.body.valueLabel || "").trim();
  const priceCents = Number(req.body.priceCents);
  const stock = Number(req.body.stock);
  if (title.length < 4 || valueLabel.length < 2 || !Number.isInteger(priceCents) || priceCents < 100 || !Number.isInteger(stock) || stock < 1) {
    return res.status(400).json({ error: "Check listing fields" });
  }
  const slug = "nova-" + Date.now() + "-" + crypto.randomBytes(3).toString("hex");
  const result = await pool.query(
    `INSERT INTO products
     (seller_id,slug,type,title,tag,age_label,inventory_label,value_label,price_cents,stock,symbol,description)
     VALUES ($1,$2,$3,$4,'NOVAVAULT OFFICIAL','Varies','As listed',$5,$6,$7,'NV','Official NovaVault listing published by NovaOfficial.')
     RETURNING *`,
    [req.user.id, slug, type, title, valueLabel, priceCents, stock],
  );
  res.status(201).json({ product: result.rows[0] });
}));

app.get("/api/admin/users", requireAdmin(async (_req, res) => {
  const result = await pool.query(
    "SELECT id,username,email,display_name,is_seller,is_admin,seller_name,verified,created_at FROM users ORDER BY created_at DESC",
  );
  res.json({ users: result.rows });
}));

async function ensureChat(buyerId, productId) {
  const productResult = await pool.query(
    "SELECT p.*, u.id AS seller_user_id, u.seller_name FROM products p JOIN users u ON u.id=p.seller_id WHERE p.id=$1 AND p.active=TRUE",
    [productId],
  );
  const product = productResult.rows[0];
  if (!product) throw Object.assign(new Error("Product not found"), { status: 404 });
  if (String(product.seller_user_id) === String(buyerId)) throw Object.assign(new Error("You cannot buy your own listing"), { status: 400 });

  const existing = await pool.query("SELECT * FROM chats WHERE buyer_id=$1 AND product_id=$2", [buyerId, productId]);
  if (existing.rows[0]) return { chat: existing.rows[0], product };

  const created = await pool.query(
    `INSERT INTO chats (buyer_id,seller_id,product_id)
     VALUES ($1,$2,$3) RETURNING *`,
    [buyerId, product.seller_user_id, productId],
  );
  await pool.query(
    "INSERT INTO messages (chat_id,sender_kind,body) VALUES ($1,'system',$2)",
    [created.rows[0].id, `Conversation started about “${product.title}”. No payment has been made yet.`],
  );
  return { chat: created.rows[0], product };
}

app.post("/api/chats", requireAuth(async (req, res) => {
  try {
    const { chat } = await ensureChat(req.user.id, Number(req.body.productId));
    res.status(201).json({ chatId: String(chat.id) });
  } catch (error) {
    res.status(error.status || 500).json({ error: error.message || "Could not create chat" });
  }
}));

app.post("/api/orders/buy", requireAuth(async (req, res) => {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const productId = Number(req.body.productId);
    const productResult = await client.query(
      "SELECT p.*,u.id AS seller_user_id,u.seller_name FROM products p JOIN users u ON u.id=p.seller_id WHERE p.id=$1 FOR UPDATE",
      [productId],
    );
    const product = productResult.rows[0];
    if (!product || !product.active || product.stock < 1) throw Object.assign(new Error("Product is out of stock"), { status: 409 });
    if (String(product.seller_user_id) === String(req.user.id)) throw Object.assign(new Error("You cannot buy your own listing"), { status: 400 });

    let chatResult = await client.query("SELECT * FROM chats WHERE buyer_id=$1 AND product_id=$2 FOR UPDATE", [req.user.id, productId]);
    let chat = chatResult.rows[0];
    if (!chat) {
      chatResult = await client.query(
        "INSERT INTO chats (buyer_id,seller_id,product_id) VALUES ($1,$2,$3) RETURNING *",
        [req.user.id, product.seller_user_id, productId],
      );
      chat = chatResult.rows[0];
      await client.query(
        "INSERT INTO messages (chat_id,sender_kind,body) VALUES ($1,'system',$2)",
        [chat.id, `Conversation started about “${product.title}”.`],
      );
    }
    if (chat.status === "PRE_SALE") {
      const orderCode = "NX-" + crypto.randomInt(100000, 999999);
      await client.query(
        "UPDATE chats SET status='PAID',order_code=$1,updated_at=NOW() WHERE id=$2",
        [orderCode, chat.id],
      );
      await client.query("UPDATE products SET stock=stock-1 WHERE id=$1", [productId]);
      await client.query(
        "INSERT INTO messages (chat_id,sender_kind,body) VALUES ($1,'system',$2)",
        [chat.id, `Payment confirmed. ${req.user.display_name} paid $${(product.price_cents/100).toFixed(2)} for “${product.title}”. Order ${orderCode} is active. ${product.seller_name || "Seller"} can now coordinate delivery in this chat.`],
      );
    }
    await client.query("COMMIT");
    res.json({ chatId: String(chat.id) });
  } catch (error) {
    await client.query("ROLLBACK");
    console.error(error);
    res.status(error.status || 500).json({ error: error.message || "Could not complete purchase" });
  } finally {
    client.release();
  }
}));

app.get("/api/chats", requireAuth(async (req, res) => {
  const result = await pool.query(
    `SELECT c.*, p.title AS product_title,p.price_cents,p.symbol,
            buyer.display_name AS buyer_name,
            COALESCE(seller.seller_name,seller.display_name) AS seller_name
     FROM chats c
     JOIN products p ON p.id=c.product_id
     JOIN users buyer ON buyer.id=c.buyer_id
     JOIN users seller ON seller.id=c.seller_id
     WHERE c.buyer_id=$1 OR c.seller_id=$1
     ORDER BY c.updated_at DESC`,
    [req.user.id],
  );
  const chats = [];
  for (const chat of result.rows) {
    const messages = await pool.query(
      `SELECT m.id,m.sender_id,m.sender_kind,m.body,m.created_at,u.display_name AS sender_name
       FROM messages m LEFT JOIN users u ON u.id=m.sender_id
       WHERE m.chat_id=$1 ORDER BY m.created_at ASC`,
      [chat.id],
    );
    chats.push({ ...chat, messages: messages.rows });
  }
  res.json({ chats });
}));

app.post("/api/chats/:id/messages", requireAuth(async (req, res) => {
  const chatId = Number(req.params.id);
  const body = String(req.body.body || "").trim();
  if (!body || body.length > 2000) return res.status(400).json({ error: "Message must be 1–2000 characters" });
  const access = await pool.query("SELECT * FROM chats WHERE id=$1 AND (buyer_id=$2 OR seller_id=$2)", [chatId, req.user.id]);
  if (!access.rows[0]) return res.status(404).json({ error: "Chat not found" });
  await pool.query("INSERT INTO messages (chat_id,sender_id,sender_kind,body) VALUES ($1,$2,'user',$3)", [chatId, req.user.id, body]);
  await pool.query("UPDATE chats SET updated_at=NOW() WHERE id=$1", [chatId]);
  res.status(201).json({ ok: true });
}));

app.post("/api/chats/:id/status", requireAuth(async (req, res) => {
  const chatId = Number(req.params.id);
  const action = String(req.body.action || "");
  const access = await pool.query(
    "SELECT c.*,buyer.display_name AS buyer_name,COALESCE(seller.seller_name,seller.display_name) AS seller_name FROM chats c JOIN users buyer ON buyer.id=c.buyer_id JOIN users seller ON seller.id=c.seller_id WHERE c.id=$1 AND (c.buyer_id=$2 OR c.seller_id=$2)",
    [chatId, req.user.id],
  );
  const chat = access.rows[0];
  if (!chat) return res.status(404).json({ error: "Chat not found" });

  let next = null;
  let body = null;
  if (action === "deliver" && String(chat.seller_id) === String(req.user.id) && chat.status === "PAID") {
    next = "DELIVERED";
    body = `${chat.seller_name} marked the order as delivered. ${chat.buyer_name} can review and confirm receipt.`;
  } else if (action === "confirm" && String(chat.buyer_id) === String(req.user.id) && chat.status === "DELIVERED") {
    next = "COMPLETED";
    body = `${chat.buyer_name} confirmed receipt. The transaction is completed.`;
  } else if (action === "dispute" && String(chat.buyer_id) === String(req.user.id) && ["PAID","DELIVERED"].includes(chat.status)) {
    next = "DISPUTED";
    body = "A dispute was opened. NovaVault support can review the order conversation and submitted evidence.";
  } else {
    return res.status(400).json({ error: "Action is not allowed for this order" });
  }

  await pool.query("UPDATE chats SET status=$1,updated_at=NOW() WHERE id=$2", [next, chatId]);
  await pool.query("INSERT INTO messages (chat_id,sender_kind,body) VALUES ($1,'system',$2)", [chatId, body]);
  res.json({ status: next });
}));

app.use(express.static(staticDir));
app.get("*", (_req, res) => res.sendFile(path.join(staticDir, "index.html")));

if(pool) await initDb();
app.listen(PORT, "0.0.0.0", () => console.log(`NovaVault server listening on ${PORT}${pool?" with database":" awaiting DATABASE_URL"}`));
