# Einkaufszettel

Android-App, die eine handgeschriebene Einkaufsliste fotografiert (oder aus der Galerie lädt), die Handschrift liest und daraus eine Liste zum Abhaken macht – sortiert nach Abteilungen im Supermarkt.

## Funktionen

- **Fotografieren** oder **Bild hochladen** (bis zu 4 Bilder auf einmal, z. B. mehrere Zettel)
- Bilder aus der Galerie per **Teilen → Zur Einkaufsliste** an die App schicken
- Erkannte Einträge vor dem Übernehmen prüfen und korrigieren; schwer lesbare Wörter sind gelb markiert
- Liste nach Abteilungen (Obst & Gemüse, Milch & Käse, …), antippen = abhaken, „Im Wagen“-Bereich
- Artikel von Hand hinzufügen („2 Milch“ → Menge wird erkannt), löschen mit Rückgängig
- Liste als Text teilen (WhatsApp, Signal …)

## Handschrifterkennung

| Modus | Kosten | Qualität |
|---|---|---|
| **Gemini** (empfohlen) | Gratis-Tarif, keine Kreditkarte | sehr gut, auch bei Handschrift |
| **Offline** (ML Kit, am Handy) | gratis, ohne Konto | gut bei Druckschrift, mäßig bei Handschrift |

Gemini einrichten: in der App unter **Einstellungen → Google AI Studio öffnen**, Schlüssel erstellen, einfügen. Ohne Schlüssel liest die App automatisch offline.

## Build

Jeder Push auf `main` baut per GitHub Actions eine signierte APK und legt sie als Release an (Tab **Releases**).
Die APK ist immer mit demselben Schlüssel (`keystore/`) signiert, damit Updates über die alte Version installiert werden können.
