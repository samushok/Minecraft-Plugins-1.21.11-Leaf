# Nexora Marketplace MVP

A standalone front-end marketplace prototype built without Replit, Lovable, Supabase, or any hosted backend.

## What is included

- Responsive marketplace home page
- Search, game/category filters and sorting
- Product detail modal
- Local demo registration/login
- Seller listing creation
- Seller dashboard
- Buyer order flow
- Demo balances
- Order status progression
- Basic dispute state
- Seller profile
- Demo admin account and overview
- Browser localStorage persistence
- Dark/light mode

## Demo admin

- Email: `admin@nexora.demo`
- Password: `demo`

## Important

This is an MVP/demo. Payments, identity verification, platform APIs, escrow and real payouts are intentionally not connected. Those need a production backend and a compliant payment/marketplace provider before launch.

All marketplace categories shown in the demo should only be enabled when permitted by the relevant game/platform and payment provider.

## Run locally

Open `index.html` in a browser, or serve the folder with any static HTTP server.

## Architecture direction for production

The current localStorage data layer is intentionally isolated from the UI logic so it can later be replaced with a real API/database. A production version should add:

- server-side authentication
- PostgreSQL or equivalent database
- secure sessions
- object storage for listing images
- payment processor marketplace/connected-account flow
- KYC/identity checks where required
- rate limiting and anti-fraud controls
- moderation tooling
- audit logs
- transactional email
- webhook verification
- automated tests and CI/CD
