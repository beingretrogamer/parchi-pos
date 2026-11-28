# Parchi POS

A light point-of-sale for an Android phone. Tap what the customer ordered,
press **Print**, and two slips come out of the network thermal printer:

1. **The bill**, with prices, for the customer.
2. **The parchi**, with items and quantities in big type and no prices, for
   the kitchen.

Both carry the same **token number**. The kitchen calls it out, and the
customer holding that bill collects the order. Tokens start again at 1 every
morning.

```
phone (this app)  ──TCP──────▶  Wi-Fi/LAN printer (192.168.x.x:9100)
                  ──Bluetooth─▶  Bluetooth printer (paired in Android settings)
```

The bill and the parchi can go to one printer, or to two: a bill printer at
the counter and a kitchen printer. Either can be Wi-Fi/LAN or Bluetooth.

No server, account or internet is needed to bill. Sales are stored on the
phone and can be exported as CSV.

Started as a copy of [escpos-print-bridge](https://github.com/beingretrogamer/escpos-print-bridge).
The printer queue and connection handling came across from it. The HTTP
bridge did not: this app prints by itself, so it needs no foreground service
and has nothing listening on a port.

## Set up

1. **Settings → Printers → Bill printer**:
   - **Wi-Fi / LAN**: enter the printer IP and port (usually `9100`). The phone
     must be on the same Wi-Fi as the printer.
   - **Bluetooth**: first pair the printer in Android's own Bluetooth settings
     (the PIN is usually `0000` or `1234`). Then tap **Choose Bluetooth
     printer** and pick it from the paired devices; likely printers are listed
     first. On Android 12 and later the app asks once for the **Nearby
     devices** permission. It never scans and never needs location.

   Choose 80 or 58 mm paper (most Bluetooth printers are 58 mm), then press
   **Test bill printer**.
2. **Kitchen printer (for the parchi)**: leave it on **Same as the bill
   printer** for one printer. Otherwise pick its own Wi-Fi/LAN or Bluetooth
   printer and its paper, then press **Test kitchen printer**. With two
   printers, each sale prints the bill at the counter and the parchi in the
   kitchen at the same time. If one of them fails, **Retry** reprints only that
   slip.
3. In the same screen, fill in the address, phone, optional GSTIN and
   footer. These are printed at the top and bottom of the bill. The bill
   starts with a drawn Scoops & Sips logo (previewed in Settings). Turn it
   off to print the shop name as text instead. **Small print**, on by
   default, uses the printer's smaller font for shorter slips.
4. **Menu**: the app starts with the TUB's × Scoops & Sips menu already
   loaded: 73 items in 16 categories, copied from the boards. Items with
   sizes or flavours are one tile each, and their price field reads
   `Half 40 / Full 80`. **Paste a list** takes one item per line in the same
   format, for example `Veg Momos, Half 40 / Full 80, Momos`. Use ↑ ↓ to put
   the bestsellers first. **Reset to the board menu** brings the original
   menu back.

## At the counter

- Tap a tile to add it, and tap again for more. For momos, waffles, scoops,
  mojitos and similar items, the app asks which size or flavour you want.
- **Edit while billing:** tap a line in the cart to change its name, rate or
  quantity, or to add a note for the kitchen, such as "less spicy" or "Mint
  mojito" for a combo. Notes print on both slips. The edit applies to that
  bill only and never changes the menu.
- **Search** filters the whole menu as you type.
- **+ Custom**, next to the search box, rings up something that isn't on the
  menu. If nothing matches a search, the last tile offers to add what you
  typed, so only the price is left to fill in. Tick **Save to menu for next
  time** (and optionally pick a category) to keep it on the menu as a tile. If
  the menu already has a single-price item of that name, its price is updated
  instead of adding a duplicate.
- **Discount** in the order header takes a percent or a rupee amount off the
  whole bill, with an optional reason. The bill prints the subtotal, the
  discount and the total.
- Optionally enter a customer name, pick **Cash**, **UPI** or **Credit**, then
  press **Print**, which prints the bill and the parchi.
- **Credit (udhaar)** puts the bill on the customer's account and needs their
  name. The name box suggests customers who already have an account.
- **Save** next to it records the sale without printing either slip. It gets a
  token like any other sale, and you can print it later from **Sales**.
- **Missed a bill on an earlier day?** In **Sales**, go back to that day and
  tap **+ Add a missed bill for this day**, or tap **📅 Today** on the order and
  pick the date. That one order goes on that day, with the next token there.
  Every new order starts on today again.
- **Bill on the wrong day?** In **Sales**, tap it and pick **Change date**. It
  moves to that day with the next token there.
- **Wrong payment mode?** Tap the bill in **Sales** and pick **Change
  payment**, then Cash, UPI or Credit. The day's split and the customer's
  balance follow.
- The sale is saved **before** anything prints, and the cart clears straight
  away for the next customer. If a printer is off or out of range, the app
  says which slip didn't print and offers **Retry** for just that slip. The
  sale is never lost, and you can reprint it later from **Sales**.

## Sales

- Shows the day's total, the cash/UPI split, item-wise quantities, and every
  bill. Use ‹ › to move between days.
- The summary also shows sales on credit, discounts given, and dues
  collected that day, split by Cash and UPI.
- Tap a bill to reprint the bill, the parchi or both, or to **void** it.
  Voiding asks for a reason and will not go ahead without one. A voided bill
  stays listed, struck through, with its reason, and is left out of the
  totals.

## Dues (credit accounts)

- **Dues** at the top of the till lists every credit customer and what they
  owe, largest first, with the total outstanding.
- Tap a customer for their statement: each credit bill (+) and payment (−),
  with the balance after each.
- **Receive payment** records money against the balance. The amount starts at
  the full balance, so settling up is one tap. Pick Cash or UPI and add a note
  if needed. A **discount allowed** (for example, rounding 1,005 down to
  1,000) also reduces the balance. Afterwards you can print a receipt slip
  that shows the balance left.
- Tap a payment to reprint its receipt, or to void it. Voiding needs a reason
  and puts the amount back on the balance.
- **Share statement** sends the account as plain text to WhatsApp.
- Balances are always worked out from the bills and payments, so voiding a
  credit bill or changing its payment mode corrects the balance by itself.
  Payments received are kept on this phone only and are not sent by cloud
  sync.
- **Share summary** sends a plain-text summary to WhatsApp or similar.
- **Export day** and **Export all sales** save a CSV through the system's
  Save dialog, so you can put it in Downloads or Drive. The CSV has one row
  per item line: date, time, token, bill id, customer, payment, status, void
  reason, item, qty, rate, amount, bill discount, discount reason and bill
  total.

## Parchi Cloud

Every device of a shop shares one set of bills, payments, customers and one
menu through Parchi Cloud. The till never waits for the internet: a bill is
saved and printed first, and goes up when the device is online.

**Connecting a device:**

1. The owner makes a code in the portal: **Devices → Add a device**.
2. On the device, open **Settings → Parchi Cloud**, type the code and a name
   for the device ("Counter"), and press **Connect**.

What happens on connect:

- **Uploads.** This device's own bills and all its dues payments go up.
  Bills it saw from other devices (through IceCreamOS) stay where they are;
  each device uploads its own.
- **Its letter.** It keeps its old letter if it's free.
- **Menu.**
  - If the account already has a menu, it replaces the device's. The old one
    is kept in `menu.backup.json`.
  - If the account has none yet, the Parchi Cloud screen offers to upload
    the device's menu. That needs an owner's or manager's PIN.
- **Bill header.** It works the same way as the menu.
- **Customers.** Every credit name becomes a customer. The id comes from the
  name, so two devices that know "Ramesh" make the same customer.

From then on:

- **Sync timing.** It syncs every 10 seconds while the app is open and right
  after every change. If bills are still waiting when the app closes, they go
  as soon as there is network.
- **Status dot.** The dot in the till's header is green when everything is
  up. It turns amber with a count when bills are waiting, and red when sync
  has stopped. Tap it for details.
- **Other devices' bills** appear in Sales, with the device's name.
- **Edits on two devices both survive.** A void on one device and a payment
  change on another are separate edits. If two devices edit the same thing,
  the later edit wins.
- **Invoice numbers.** New bills get one, like `A/26-27/000123`, printed
  under the token. Each device has its own series per financial year, so
  devices never clash, even offline.
- **Menu and bill header** come from the account. They are edited in the
  portal, or on the device after an owner or manager unlocks it (below).
- **Clock.** Times follow Parchi Cloud's clock, so a device with a wrong
  clock still files bills on the right day.
- **History.** A new device downloads the last 90 days plus every credit bill
  and payment. Opening an older day in Sales fetches it.

**Owners and managers on the device.** Each sets a PIN in the portal under
**My account**. It is checked on the device, offline.

- **Unlock.** Tap the padlock in the till's header and type the PIN. The
  header then shows who is unlocked and the time left. While unlocked, the
  Menu screen and the bill header in Settings can be edited, and a custom
  item can be saved to the menu. It locks again after 5 minutes without a
  tap, when Parchi is left, or with **Lock now**.
- **Till rules.** The owner decides in the portal what needs a PIN at the
  counter: custom items, price changes, discounts over a limit, bills on
  another day, voids, payment changes, voiding a dues payment, marking items
  sold out, and seeing the day's totals. When one needs a PIN, the device asks
  at that moment. The approval is kept on the bill and shown in the portal.
- **Wrong PINs.** After 5 wrong PINs in a row, the pad locks for a minute.
- **Sold out.** Long-press an item on the till, or use **Sold out** on the
  Menu screen. It greys out on every device of the outlet until it's back.
- **Edits made offline** go up when the device is back online. If the
  person's PIN changed meanwhile, the account refuses the edit, and the device
  goes back to the account's version. The Parchi Cloud screen says what
  happened.

**App lock** (optional; the owner switches it on in the portal under Till
rules). The device asks for a PIN when Parchi is opened, and after it sits
unused for the set minutes.

- **Who can open it.** The outlet's staff PIN opens it, and so does any
  owner's or manager's own PIN.
- **Forgotten PIN.** The owner can change the staff PIN, or switch the lock
  off, in the portal. The device picks that up at its next sync.
- **Lost device.** Remove it in the portal; its data stays behind the lock.
- Devices that aren't connected never lock.

**Disconnect this device**, at the bottom of the Parchi Cloud screen, turns it
back into a till of its own and keeps everything on it. It needs the owner's
PIN. Remove it in the portal as well, to free its place on the licence.

## Cloud sync (IceCreamOS)

*For devices not connected to Parchi Cloud. Connecting a device to Parchi
Cloud switches IceCreamOS sync off for it.*

Every bill can also go to IceCreamOS, where it becomes an ordinary retail sale:
stock is deducted, and the sale, payment and accounts are booked.

1. In IceCreamOS, open **Settings → Parchi POS → Add phone**. Give the phone a
   name and pick where its sales take stock from. Copy the key it shows.
2. On the phone, open **Settings → Cloud sync**, paste the key and press
   **Sync now**.

Each phone then sends **every bill it holds, old ones included**, and after
that each new bill and each edit: void, restore, change date, change payment.
The phone never waits for the internet. Bills rung up offline go out on the
next sync, which runs after each sale, whenever the till is opened, and every
five minutes while it is open. Bills not sent yet show **↑ not synced** in
**Sales**.

In IceCreamOS, a bill posts once each item name on it is linked to a
material. A bill posts even if IceCreamOS shows no stock for it: the sale
happened, so it is booked and that location's stock goes negative, which
shows what was never received. **Settings → Parchi POS** lists the unlinked
names, any bill that could not post (for example a made-to-order item with
no recipe), and a day-by-day check of what each phone rang up against what
reached the books.

A bill discount is booked as charged: the lines are reduced in proportion, so
IceCreamOS's total and GST match the bill. **Credit (udhaar) bills are not
booked in IceCreamOS yet**: they reach it and are held, and Dues on the phone
remains the record of what each customer owes. Payments taken in Dues are not
sent yet either.

Use one key per phone. To move a phone to a new key, press **New key** on that
phone's entry rather than adding the phone again, or its history arrives a
second time as another phone's.

### Several phones at once

Once synced, the phones work as one till:

- **Tokens carry the phone's letter.** IceCreamOS gives each phone a letter,
  so the counter phone prints A1, A2, … and the window phone B1, B2, …. Two
  phones billing at the same moment never hand out the same token. A phone
  prints plain numbers until its first sync.
- **Sales shows every phone.** Other phones' bills appear with the phone's
  name, and the day's totals cover all phones, with this phone's share
  underneath.
- **Any phone can edit any bill.** Void, restore, change date or change
  payment works on another phone's bill too. If two phones edit the same bill,
  the later edit wins.

Each phone still keeps its own menu and shop details for now; a menu managed
in IceCreamOS is next.

A phone sees the others' bills and edits when it syncs: after each sale,
when the till or Sales opens, and every five minutes.

## Limits, on purpose

- Prices are GST-inclusive. If a GSTIN is set, the bill says "Prices are
  inclusive of GST", but it does not split out the tax, because items carry
  no GST rate.
- Printers' built-in code pages have no ₹ and no Devanagari. ₹ prints as
  `Rs.`, and other non-ASCII text prints as `?`, so give items English names.
- Without cloud sync, data lives only on this phone, so export now and then.
  Uninstalling the app deletes the sales, including any not yet synced.

## Where the data lives

Everything is in one SQLite database on the phone, `parchi.db`: bills, dues
payments and the menu, with every amount in paise (₹45.50 is stored as 4550).
It is the same shape Parchi Cloud syncs, field for field.

Before 0.6.2 the app kept `sales.jsonl`, `receipts.jsonl` and `menu.json`.
The first start of 0.6.2 copies them into the database in one go and renames
them `*.imported`, so they stay on the phone as a backup.

## Install

**[Download the latest APK](../../releases/latest/download/parchi-pos.apk)**

Open that link on the phone and allow "install unknown apps" for your browser.

## Build

No dependencies. CI (`.github/workflows/build.yml`) builds the APK on every
push to `main`. A `v*` tag publishes a release:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Locally: JDK 17, the Android SDK, and Gradle 8.7, then `gradle testDebugUnitTest assembleDebug`.

The unit tests include the Parchi Cloud protocol fixtures in
`app/src/test/resources/protocol`, copied from `parchi-cloud/protocol/fixtures`:
the phone and the server must pass the same merge, totals and invoice cases.
The store tests run on real SQLite through Robolectric, a test-only dependency
that never reaches the APK.

Two tests run the app's real sync code and screens against a real Parchi
Cloud server, and are skipped unless one is given (CI skips them):

```bash
# in parchi-cloud: npm run dev   (and an admin login on it)
PARCHI_E2E_URL=http://127.0.0.1:8787 PARCHI_E2E_ADMIN=admin PARCHI_E2E_PASSWORD=… \
  gradle testDebugUnitTest --tests '*E2ETest*'
```
