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
  **Print bill & parchi**.
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

## Limits, on purpose

- Prices are GST-inclusive. If a GSTIN is set, the bill says "Prices are
  inclusive of GST", but it does not split out the tax, because items carry
  no GST rate.
- Printers' built-in code pages have no ₹ and no Devanagari. ₹ prints as
  `Rs.`, and other non-ASCII text prints as `?`, so give items English names.
- Data lives only on this phone. Export now and then. Uninstalling the app
  deletes the sales.

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
