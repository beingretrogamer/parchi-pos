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
phone (this app)  ──TCP──▶  printer (192.168.x.x:9100)
```

There is no server, account or internet involved. Sales are stored on the
phone and can be exported as CSV.

Started as a copy of [escpos-print-bridge](https://github.com/beingretrogamer/escpos-print-bridge).
The printer queue and connection handling came across from it. The HTTP
bridge did not: this app prints by itself, so it needs no foreground service
and has nothing listening on a port.

## Set up

1. **Settings**: enter the printer IP and port (usually `9100`), choose 80 or
   58 mm paper, then press **Test print**. The phone must be on the same Wi-Fi
   as the printer.
2. In the same screen, fill in the address, phone, optional GSTIN and
   footer. These are printed at the top and bottom of the bill. The bill
   starts with a drawn Scoops & Sips logo (previewed in Settings). Turn it
   off to print the shop name as text instead. **Small print**, on by
   default, uses the printer's smaller font for shorter slips.
3. **Menu**: the app starts with the TUB's × Scoops & Sips menu already
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
  typed, so only the price is left to fill in.
- Optionally enter a customer name, pick **Cash** or **UPI**, then press
  **Print**, which prints the bill and the parchi.
- **Save** next to it records the sale without printing either slip. It gets a
  token like any other sale, and you can print it later from **Sales**.
- **Missed a bill on an earlier day?** In **Sales**, go back to that day and
  tap **+ Add a missed bill for this day**, or tap **📅 Today** on the order and
  pick the date. That one order goes on that day, with the next token there.
  Every new order starts on today again.
- **Bill on the wrong day?** In **Sales**, tap it and pick **Change date**. It
  moves to that day with the next token there.
- **Wrong payment mode?** Tap the bill in **Sales** and pick **Change payment
  to UPI** (or **to Cash**). The day's Cash and UPI split follows.
- The sale is saved **before** anything prints, and the cart clears straight
  away for the next customer. If the printer is off, the app offers **Retry**.
  The sale is never lost, and you can reprint it later from **Sales**.

## Sales

- Shows the day's total, the cash/UPI split, item-wise quantities, and every
  bill. Use ‹ › to move between days.
- Tap a bill to reprint the bill, the parchi or both, or to **void** it. A
  voided bill stays listed, struck through, and is left out of the totals.
- **Share summary** sends a plain-text summary to WhatsApp or similar.
- **Export day** and **Export all sales** save a CSV through the system's
  Save dialog, so you can put it in Downloads or Drive. The CSV has one row
  per item line: date, time, token, bill id, customer, payment, status, item,
  qty, rate, amount and bill total.

## Cloud sync (IceCreamOS)

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
- **One menu and one set of shop details.** A change made on any phone reaches
  the others on their next sync. The first phone to sync shares its menu and
  shop details; every other phone takes them, and keeps its previous menu in
  `menu.backup.json`. If two phones change the menu at the same time, the
  first change to reach IceCreamOS is kept and Settings says so. The printer
  address stays per phone.

A phone sees the others' bills and changes when it syncs: after each sale,
when the till or Sales opens, and every five minutes.

## Limits, on purpose

- Prices are GST-inclusive. If a GSTIN is set, the bill says "Prices are
  inclusive of GST", but it does not split out the tax, because items carry
  no GST rate.
- Printers' built-in code pages have no ₹ and no Devanagari. ₹ prints as
  `Rs.`, and other non-ASCII text prints as `?`, so give items English names.
- Without cloud sync, data lives only on this phone, so export now and then.
  Uninstalling the app deletes the sales, including any not yet synced.

## Install

**[Download the latest APK](../../releases/latest/download/parchi-pos.apk)**

Open that link on the phone and allow "install unknown apps" for your browser.

## Build

No dependencies. CI (`.github/workflows/build.yml`) builds the APK on every
push to `main`. A `v*` tag publishes a release:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Locally: JDK 17, the Android SDK, and Gradle 8.7, then `gradle assembleDebug`.
