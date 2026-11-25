# Project Friday — Wearable AI Pendant V1 Blueprint (Cardboard College Prototype)

> **Target Build:** Bare-minimum wearable necklace prototype for live all-day campus testing.  
> **Overall Target Footprint:** `~28 mm × 35 mm × 11 mm` (smaller than a smartwatch body).

---

## 1. Verified Bill of Materials (From Bench Inspection)

1. **MCU & Mic Board:** **Seeed Studio XIAO nRF52840 Plus / Sense Plus** (`21 mm × 17.5 mm`)
   - *Top side:* White/silver RF shield (`XIAO-nRF52840-Plus`), USB-C port, `RST` button, RGB/CHG LEDs, and **blue ceramic 2.4 GHz BLE chip antenna** on the left edge opposite USB-C.
   - *Bottom side:* Exposed `BAT+` and `BAT-` solder pads (and sensor/PDM microphone layout on Plus/Sense Plus).
   - *Important:* Confirm the exact `BAT+`, `BAT-`, and MEMS mic acoustic port hole labels on the PCB silkscreen before cutting your cardboard faceplate.
2. **Battery:** **PL432224 LiPo Cell (`3.87V`, `1.08 Wh`)**
   - *Actual Energy:* `1.08 Wh / 3.85 V ≈ 280 mAh` (even if advertised as 400 mAh — plan power budget around **280 mAh**).
   - *Dimensions:* `~4.3 mm thick × 22 mm × 24 mm`.
   - *Safety:* Built-in protection circuit board (BMS) is already mounted under the amber Kapton tape, with Red (`+`) and Black (`-`) leads.
3. **Power Switch:** **3-Pin SPDT Miniature Slide Switch** (3 center contact legs + 2 outer mechanical shell tabs).
4. **Prototype Body & Wearable Hardware:**
   - Thin rigid corrugated cardboard (`2–3 mm` flute) + thin cereal-box cardstock (`~0.4 mm`) for the internal insulating divider.
   - Electrical tape (or black matte tape) + hot glue / double-sided foam tape.
   - String / paracord loop (at least at the attachment ears) + metal chain / necklace cord.

---

## 2. Electrical Wiring & Charging Blueprint

```
  [ PL432224 LiPo Battery ]
  (3.87V, 1.08Wh / ~280mAh)
     │
     ├─── BLACK (-) ──────────────────────────────────────► [ XIAO BAT- Pad ]
     │
     └─── RED (+) ──► [ Switch PIN 2 (Center / Common) ]
                      [ Switch PIN 1 (Outer Left)      ] ──► [ XIAO BAT+ Pad ]
                      [ Switch PIN 3 (Outer Right)     ] ──► (Leave Unconnected)
                      [ Switch Outer Metal Shell Tabs  ] ──► (Do NOT wire to +/-)
```

### Critical Electrical Rules
1. **Cut & Strip One Wire at a Time:** Cutting both Red and Black leads simultaneously with metal scissors creates a dead short across the cell. Trim, strip, and solder **Black (`-`) first**, insulate or route it cleanly, then work on **Red (`+`)**.
2. **Insulate Every Solder Joint & Side Pin:**
   - Wrap exposed switch pins with narrow strips of electrical tape or heat-shrink tubing so they can never touch the battery's yellow protection strip or XIAO side pins.
   - Cover the exposed **side castellated pads** (`3V3`, `GND`, GPIOs) along both edges of the XIAO with electrical tape so your metal necklace chain or sweat can **never** short pins together.
3. **USB-C Charging Behavior With the Switch:**
   - On the XIAO nRF52840, USB-C (`5V`) feeds the onboard BQ2510x LiPo charger IC directly connected to `BAT+`/`BAT-`.
   - **Switch ON + USB-C plugged in:** Board powers on AND **charges the battery** (orange `CHG` LED stays lit until full, taking ~3–5 hours at the safe 50 mA default trickle rate).
   - **Switch OFF + USB-C plugged in:** Board boots from USB, **but the battery is disconnected and WILL NOT CHARGE.** Always flip the switch **ON** while charging overnight.

---

## 3. Physical Orientation & RF / Acoustic Rules (Crucial!)

1. **Orientation on Your Chest:**
   - **USB-C Port pointing DOWN** (accessible flush at the bottom edge so you can plug in a charger without opening the cardboard).
   - **Ceramic BLE Antenna (left edge of board) pointing UP / AWAY from battery foil:** Aluminum LiPo pouches act like an RF mirror/shield and kill 2.4 GHz Bluetooth range! Keep the **top-left antenna area free of battery metal and metal chains** (let the cardboard ear + string hold the chain `≥ 5–8 mm` away from the chip antenna).
2. **PDM Microphone Acoustic Port (Never Muffle It!):**
   - Locate the tiny MEMS microphone sound hole on your PCB (check top/underside carefully).
   - Mount the XIAO so that the **microphone port faces OUTWARD / UPWARD** toward your chin (`~15–22 cm` below mouth on upper chest), NOT buried against your shirt!
   - Cut a **clean `3.5–4 mm` circular hole** in the front cardboard right over the mic port. Never put glue or tape across the mic hole.
3. **Anti-Flip Two-Point Necklace Mount:**
   - Do **not** hang the pendant from a single center hole — walking makes a single-hole pendant twist sideways or flip backwards so the mic rubs against your shirt!
   - Punch **two top corner holes (`~18–20 mm` apart)** in the cardboard top flange, tie a short **nylon string / thread loop** through each hole, and clip your metal chain to those cord loops. That guarantees the mic always stays facing forward and upright, AND keeps conductive metal off the board.

---

## 4. 4-Layer Cardboard Sandwich Assembly Blueprint

```
  FRONT VIEW (Facing Outward)              SIDE CROSS-SECTION (Layered Stack)
  ┌───(o)─────────────(o)───┐  <-- 2 cord  ┌── Shirt / Chest (Back)
  │    Two-point chain      │      loops   │  [ Layer 1: Black-Taped Cardboard Backplate ]
  │ ┌─────────────────────┐ │              │  [ Layer 2: U-Shaped Cardboard Frame      ]
  │ │   [BLE Antenna]     │ │              │  │          holding LiPo Battery + Switch   │
  │ │                     │ │              │  [ Layer 3: Thin Cardstock + Tape Divider   ]
  │ │   (O) Mic Hole      │ │ <--- 4mm     │  │          (protects LiPo pouch from pins) │
  │ │        3.5-4mm      │ │      port    │  [ Layer 4: XIAO PCB (USB-C flush at bottom)]
  │ │   [.] Tiny LED Hole │ │              │  [ Layer 5: Front Cardboard Faceplate       ]
  │ └──────[ USB-C ]──────┘ │              └── Outward (Air / Voice)
  └───────────┬─────────────┘
       Switch notched into side edge
```

### Step-by-Step Bench Build Order (Takes ~25–35 Minutes)

1. **Step 1 — Flash & Dry-Test Before Soldering:**
   - Plug the bare XIAO into USB-C, flash the latest [xiao_friday_pendant.ino](file:///f:/Avaneesh/projects/Friday/firmware/xiao_friday_pendant/xiao_friday_pendant.ino), open Serial Monitor (`115200`), verify BLE connects to your Android phone and *"Friday"* triggers cleanly.
2. **Step 2 — Solder Switch & Battery Leads:**
   - Tin the middle leg (Pin 2) and one outer leg (Pin 1) of the slide switch.
   - Solder Battery **Red (`+`)** to Switch **Pin 2**; solder a short (~25 mm) thin red jumper wire from Switch **Pin 1** to XIAO **`BAT+`** pad on the underside.
   - Solder Battery **Black (`-`)** directly to XIAO **`BAT-`** pad on the underside.
   - **Quick Test:** Flip switch ON -> LED should pulse/boot and connect to your phone over BLE! Flip switch OFF -> LED dies immediately. Plug in USB-C with switch ON -> orange `CHG` LED illuminates. Wrap all exposed switch legs and XIAO side castellations with electrical tape.
3. **Step 3 — Cut Cardboard Layers (`~28 mm × 35 mm` body + `7 mm` top ear tab):**
   - **Layer 1 (Backplate):** `28 mm × 42 mm` cardboard (top `7 mm` is the flange for the two necklace holes). Wrap back with black electrical tape for sweat resistance.
   - **Layer 2 (Battery Spacer Frame):** Cut a `28 mm × 35 mm` rectangle with the **center cut out (`23 mm × 25 mm`)** so the LiPo battery sits *inside* the cardboard wall instead of being crushed, and cut a **side notch** on the right wall sized tightly for your slide switch body. Route the switch into that notch and tack it from behind with a tiny drop of hot glue (avoid getting glue into the sliding slot!).
   - **Layer 3 (Protective Divider):** Cut a `28 mm × 35 mm` piece of **thin cereal-box cardstock** covered in tape. Route the `BAT+`/`BAT-` wires smoothly around the edge onto the XIAO underside, and place this divider **between the battery pouch and the XIAO** so no sharp solder point can ever puncture the silver LiPo cell.
   - **Layer 4/5 (XIAO Seat & Front Faceplate):** Seat the XIAO USB-C flush at the bottom edge (offset the battery/cardboard slightly if needed so the top-left BLE antenna sits clear!). Punch the **`3.5–4 mm` microphone hole** and optional `1 mm` LED pinhole in the front faceplate, tape/glue the edges cleanly, and wrap the perimeter neatly in black tape so it looks clean and intentional on campus.
4. **Step 4 — Mount Lanyard Loops & Chain:**
   - Punch two holes (`~18 mm` apart) in the top cardboard flange, reinforce the holes with a layer of tape before punching so cardboard doesn't tear, tie short string/cord loops through both holes, and clip your necklace chain to the string loops.
5. **Step 5 — Pre-Campus Check Tonight:**
   - Charge fully (switch **ON**, USB-C plugged in until orange `CHG` LED turns off).
   - Wear it around your room for 10 minutes with music/walking to verify chest acoustic level (`RMS`) and zero shirt-rustle false triggers!

---

## 5. Battery Life & Practical Campus Notes (`1.08 Wh / ~280 mAh`)

- **Current Firmware Power Estimate:** With continuous PDM DMA + `LED_BLUE` solid ON when connected + busy-waiting `loop()`, the board draws roughly `~10–15 mA` idle (`~16–24 hours` theoretical, **~10–14 hours real-world** depending on how often noisy hallways wake VAD).
- **Recommended Firmware Power Optimization (Optional Quick Tweak):**
  - Turn `LED_BLUE` **OFF** while connected in class (saves ~2–4 mA AND avoids a glowing blue beacon on your chest in lectures!), flashing only briefly when disconnected or when *"Friday"* triggers.
  - Add a short `delay(4)` idle yield at the end of `loop()` so FreeRTOS enters low-power System ON wait (`sd_app_evt_wait()`) between audio frames instead of busy-looping at 64 MHz.
