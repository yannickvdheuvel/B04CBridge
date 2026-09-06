# B04C-BF protocol

Wat we van het HUIYE/BIKEGO B04C-BF display weten. Alles hieronder is op het echte
apparaat getest, niet uit documentatie overgenomen — waar iets nog een vermoeden is,
staat dat er expliciet bij.

Reproduceren kan met `tools/build-and-run.ps1`: die praat rechtstreeks vanaf Windows
met het display, zodat je een protocolvraag in seconden beantwoordt in plaats van via
push → GitHub Actions → APK → telefoon.

## Verbinding

| | |
|---|---|
| BLE-naam | `B04C-BF` |
| Adres (deze fiets) | `70:DE:F9:D3:A0:9E` |
| Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` (Nordic UART) |
| Schrijven | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| Notificaties | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |
| AES-sleutel | `2CTDU40qNyCgTjb1` (ASCII, AES-128-ECB, NoPadding) |

Het display accepteert **één** verbinding tegelijk. Zolang de laptop-rig eraan hangt,
komt de telefoon er niet bij, en andersom.

### Verbindingsgedrag

Uit een Android-bugreport van de originele BIKEGO-app, opgenomen op 2026-09-05:

- BIKEGO gebruikt gewoon `BluetoothGatt` met `isDirect=true`, en vraagt MTU 512 aan; het
  display antwoordt met 515. Onze app ziet exact hetzelfde.
- Als de link wegvalt (`Reason = 8`) zet **Android zelf** de ACL-link kort daarna weer op,
  terwijl de GATT-client van de app op dat moment allang gesloten is. "Apparaat verbonden"
  op systeemniveau zegt dus niets over of de app een bruikbare sessie heeft; houd altijd je
  eigen GATT- en authenticatiestatus bij.
- Omdat het display dan niet meer adverteert, levert scannen niets op. BIKEGO herstelt de
  verbinding door herhaaldelijk een nieuwe GATT-client te maken en direct te verbinden met
  het bekende adres — twee keer een `Direct connection timeout`, de derde keer raak. Onze
  `BleManager` doet dat nu ook, en onthoudt het adres over herstarts heen.

Terzijde, maar het verklaart wel waarom dit project bestaat: in datzelfde bugreport staan
twee native crashes van `com.huiye.ebike` in `libmapbox-common.so` (SIGABRT,
`jni::PendingJavaException`, thread `MB Search`). De navigatie van de originele app is dus
aantoonbaar zelf stuk.

## Frames

```
55 AA LEN DIR TARGET SUB PARAM  <payload...>  CS1 CS2
```

`LEN` is de payloadlengte, dus een frame is altijd `9 + LEN` bytes. `DIR` is `0x11`
van telefoon naar display en `0x10` de andere kant op. In de richting display → telefoon
staat op de `TARGET`-positie `0x11`, en zijn `SUB`/`PARAM` het berichttype.

Checksum over alle bytes vóór de checksum zelf:

```
CS1 = (0xFE - (sum & 0xFF)) & 0xFF
CS2 = (0x100 - ((sum >> 8) & 0xFF)) & 0xFF
```

## Authenticatie

1. Telefoon vraagt een challenge: target `0x10`, sub `0x01`, param `0x00`, payload `04`.
2. Display antwoordt met sub `0x04` en vier bytes challenge.
3. Zet die vier bytes vooraan in een blok van 16 nullen, versleutel met AES-128-ECB en
   stuur terug als target `0x10`, sub `0x20`, param `0x00`.
4. Payload `00` terug betekent gelukt.
5. Daarna tijd zetten: target `0x10`, sub `0x02`, param `0x3E`, payload = unixtijd als
   uint32 little-endian.

## Navigatie

Target `0xF1`, sub `0x03`, param `0x00`, payload van 18 bytes:

| byte | inhoud |
|---|---|
| 0 | volgnummer, loopt op |
| 1 | vast `0x02` — betekenis onbekend, mogelijk een typeveld |
| 2–4 | afstand tot de huidige manoeuvre, uint24 LE, meters |
| 5 | huidige manoeuvre |
| 6–8 | afstand tot de volgende manoeuvre, uint24 LE |
| 9 | volgende manoeuvre |
| 10–12 | afstand tot de derde manoeuvre, uint24 LE |
| 13 | derde manoeuvre |
| 14–17 | resterende totale route, uint32 LE, meters |

Navigatie stoppen: target `0xF1`, sub `0x02`, param `0x02`, payload `00`.

### Wat het scherm er echt mee doet

Getest met `nav 350 2 1200 3 2500 1 5000`:

- grote groene pijl = **huidige** manoeuvre, met de afstand ernaast (350 m → "0.3 km")
- kleine pijl rechtsboven = **volgende** manoeuvre, met eigen afstand (1200 m → "1.2 km")
- vlagsymbool = **totale** resterende route (5000 m → "5.0 km")
- de **derde** manoeuvre komt nergens op het scherm terug

Afstanden worden altijd in kilometers met één decimaal getoond; 90 m leest dus als "0.1 km".

Die tweede pijl blijft voorlopig leeg, en dat ligt niet aan het display. Een echte rit met
Google Maps op Android 16 laat zien dat de melding de volgende manoeuvre niet bevat: de
`ProgressStyle` heeft in élke meting `points=[]` en precies twee segmenten (afgelegd en
resterend), en er staat nergens een "daarna"-regel in de tekst. Google Maps publiceert die
informatie simpelweg niet. Komoot is nog niet op dit punt onderzocht.

### Manoeuvrecodes

Codes 1 t/m 20 zijn stuk voor stuk naar het display gestuurd en van het scherm afgelezen.

| code | betekenis |
|---|---|
| 1 | rechtdoor |
| 2 | linksaf |
| 3 | rechtsaf |
| 4 | flauw links |
| 5 | flauw rechts |
| 6 | scherp links |
| 7 | scherp rechts |
| 8 | omkeren |
| 10 | aankomst |

Alle negen hebben hun eigen, correcte pijl.

**Codes 9, 11 t/m 18 en 20 tonen allemaal de omkeerpijl.** Een onbekende code mag dus
nooit doorlekken naar het display: de fietser krijgt dan "keer om" te zien terwijl hij
rechtdoor moet. `Protocol.navDetailed()` zet daarom alles buiten de bekende set om naar
rechtdoor.

**Code 0 is geen bruikbare "onbekend"-waarde.** Het display zet dan zowel de bochtafstand
als de totale route op 0.0 km, terwijl de pijlen blijven staan.

**Er is geen rotondesymbool.** Rotonde-instructies worden daarom naar een gewone richting
vertaald op basis van het afslagnummer: 1e afslag rechts, 2e rechtdoor, 3e links, daarna
scherp links. Nog niet getest is of payload-byte 1 (nu vast `0x02`) een typeveld is waarmee
de manoeuvrebyte alsnog een afslagnummer wordt.

## Telemetrie

Het display stuurt uit zichzelf, zonder dat erom gevraagd wordt.

### `06/01` — live fietsdata (30 bytes)

Byte-posities zijn absoluut in het frame, dus inclusief de 7 headerbytes.

| byte | inhoud | eenheid |
|---|---|---|
| 12 | assistniveau | 0..max |
| 13 | aantal assistniveaus | |
| 14 | accu | procent |
| 16–17 | snelheid, uint16 LE | 0,01 km/h |
| 18–21 | trip, uint32 LE | 0,01 km |
| 22–25 | odometer, uint32 LE | 0,01 km |

Byte 12, 13 en 14 zijn vastgesteld door vanaf de laptop mee te lezen terwijl het
assistniveau met de knoppen werd omgezet: byte 12 liep `00` t/m `05` netjes mee, byte 13
bleef op de bovengrens staan, en byte 14 stond op `0x12` terwijl het scherm 18% toonde.

### `06/09` — ritstatistiek (25 bytes)

| byte | inhoud | eenheid |
|---|---|---|
| 7–8 | rijtijd, uint16 LE | seconden |
| 11–12 | gemiddelde snelheid, uint16 LE | 0,01 km/h |

Controle: 30,23 km over 5861 s is 18,57 km/h, en het frame meldde 18,56.

Nog onbekend in dit frame: byte 13–14 stond over twee sessies onveranderd op `0x1178`
(44,72 — mogelijk de maximumsnelheid van de rit), en byte 15 t/m 22 zijn nog niet
herleid. Kandidaten die het scherm wel toont maar wij nog niet plaatsen: vermogen in
watt en stroom in ampère.

## Nog niet onderzocht

Het display heeft een instelling "Bluetooth Unlock", en de originele BIKEGO-app kan
vermoedelijk meer: verlichting schakelen, assistniveau wijzigen, lock en anti-diefstal.
Daar is nog geen enkel frame van bekend.
