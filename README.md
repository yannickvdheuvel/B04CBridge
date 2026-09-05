# B04C Bridge — testversie

Doel: Google Maps-afslaginstructies via BLE naar een HUIYE B04C-BF display sturen, zonder BIKEGO-navigatie.

## Wat deze eerste versie doet
- scant specifiek naar `B04C-BF`;
- probeert de HUIYE/EKD01 Nordic-UART BLE service (NUS);
- vraagt MTU 64 aan;
- voert de bekende AES-128-ECB challenge/response uit;
- heeft handmatige testknoppen voor links/rechts/rechtdoor;
- kan Google Maps-notificaties uitlezen en richting + afstand naar het display sturen.

## Belangrijk
Het protocol is volledig bevestigd voor EKD01-BF. Voor B04C-BF moet de eerste handmatige test bevestigen dat HUIYE dezelfde BLE service/protocolstack gebruikt. Als de app `NUS-service niet gevonden` meldt, toont hij de gevonden service-UUID's; daarmee kan de B04C variant gericht worden aangepast.

## Bouwen
Open de map in Android Studio (JDK 17), laat Gradle synchroniseren en kies Build > Build APK(s). Installeer de debug APK op de Samsung S24 FE.

## Testvolgorde
1. Zet BIKEGO volledig uit zodat het display maar één appverbinding heeft.
2. Start B04C Bridge en geef Bluetooth-toestemming.
3. Tik `Zoek B04C en verbind`.
4. Wacht op `AUTH OK — display klaar`.
5. Tik `Test: rechts over 250 m`.
6. Als er een navigatiepijl op het B04C-display verschijnt, geef de app toegang tot meldingen en start Google Maps navigatie.

De Google Maps-parser is bewust simpel in v0.1: hij leest de zichtbare navigatiemelding. De volgende versie kan daarna robuuster worden gemaakt voor Nederlandse instructies, rotondes en de volgende twee manoeuvres.
