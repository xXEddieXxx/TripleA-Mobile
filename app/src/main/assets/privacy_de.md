# Datenschutzerklärung

> Hinweis: Dies ist eine Vorlage, keine Rechtsberatung. Vor dem öffentlichen Start muss sie von
> einer fachkundigen Person (z. B. einer Anwältin oder einem Anwalt) geprüft werden, und die
> Platzhalter in eckigen Klammern müssen ersetzt werden.

Stand: [Datum]

## 1. Verantwortlicher

[Name des Betreibers]
[Anschrift]
E-Mail: [E-Mail-Adresse]

Diese Erklärung gilt für den Online-Spielserver von TripleA Mobile („der Server“) und für die
Online-Funktionen der App. Das Spielen ohne Online-Konto auf dem eigenen Gerät überträgt keine
Daten an uns.

## 2. Welche Daten wir speichern

Wir speichern nur, was für Konten, Spiele und Chat nötig ist:

- Konto: Benutzername, Passwort (nur als Argon2id-Hash, nie im Klartext), Zeitpunkt der
  Registrierung, die Bestätigung, dass du mindestens 16 Jahre alt bist, und wann deine letzte
  Verbindung endete („zuletzt online“, für deine Freunde sichtbar).
- Profil: ein Profilbild aus den Bildern der App (Flagge oder Einheit) und ein kurzer Statustext,
  wenn du sie festlegst.
- Wertung: deine Elo-Zahl und die Zahl deiner gewerteten Spiele sowie je gewertetem Spiel die
  Änderung deiner Elo-Zahl (Spiel, Wert davor und danach, Zeitpunkt).
- Erfolge: die Abzeichen, die du erreicht hast, mit Zeitpunkt und dem Spiel, in dem du sie
  erreicht hast. Deine Siege je Nation und je Szenario, die Nationen, mit denen du ein Szenario
  gewonnen hast, und die damit freigeschalteten Profilbilder speichern wir nicht eigens; sie werden
  aus deinen Spielen berechnet.
- E-Mail-Adresse (freiwillig): Sie wird erst gespeichert, wenn du sie mit einem 6-stelligen Code
  bestätigt hast, den wir an sie senden. Wir nutzen sie nur für Bestätigungs- und
  Passwort-zurücksetzen-Codes und, wenn du es einschaltest, für einen Hinweis auf eine offene
  Kampfentscheidung, nie für Werbung oder Newsletter.
- Bestätigungscodes: der Code (nur als SHA-256-Hash), die Adresse, an die er ging, Zeitpunkte und
  die Zahl der Fehlversuche.
- Anmelde-Token: zufällige Zeichenfolgen (nur als SHA-256-Hash) mit Ablaufzeit, damit du
  angemeldet bleibst.
- Push-Token (nur in der Android-App, wenn du online spielst): eine von Google Firebase Cloud
  Messaging vergebene Kennung deines Geräts, die Plattform und wann sie zuletzt gemeldet wurde,
  damit wir dir „Du bist am Zug“ und offene Kampfentscheidungen melden können.
- Spiele: Karte, Spielregeln und Einstellungen (Sichtbarkeit, Einladungscode, Bedenkzeit,
  Zuschauer), wer welche Nation spielt, Bereitschaft, Uhren, Urlaubstage, Daueraufträge und
  Verteidigungsmodus, ob du offene Kampfentscheidungen per E-Mail erhalten möchtest, der Spielstand
  (Speicherdateien) und das Ergebnis.
- Chat: Lobby-, Spiel-, Zuschauer- und private Nachrichten mit Absender, Kanal und Zeitpunkt sowie
  bis wohin du einen Kanal gelesen hast.
- Freunde und Blockierungen: wen du als Freund hast oder angefragt hast und wen du blockierst.
- Meldungen: wer wen meldet, der Grund und gegebenenfalls die gemeldete Chatnachricht.
- IP-Adressen: nur kurzzeitig im Arbeitsspeicher für die Begrenzung von Anfragen (gegen
  Missbrauch) und gegebenenfalls in Server-Protokollen.

Wir setzen keine Cookies, kein Tracking, keine Analyse-Werkzeuge und keine Werbung ein.

## 3. Zwecke und Rechtsgrundlagen

- Konto, Spiele, Chat, Freunde, Push-Benachrichtigungen, E-Mail-Bestätigung, Passwort-Zurücksetzen und
  Hinweise auf offene Kampfentscheidungen per E-Mail (nur wenn du sie einschaltest): um dir den Dienst
  bereitzustellen, den du mit der Registrierung nutzt (Art. 6 Abs. 1 lit. b DSGVO, Vertrag).
- Begrenzung von Anfragen, Server-Protokolle und Meldungen: um den Server und seine Nutzer vor
  Missbrauch und Angriffen zu schützen (Art. 6 Abs. 1 lit. f DSGVO, berechtigtes Interesse an
  einem sicheren Betrieb).

Die Kontodaten (Benutzername, Passwort, Altersbestätigung) brauchen wir, um dir ein Online-Konto
bereitzustellen; ohne sie kannst du nur offline spielen. Die Angabe einer E-Mail-Adresse ist
freiwillig; ohne sie kannst du ein vergessenes Passwort nicht zurücksetzen und keine Hinweise per
E-Mail erhalten.

## 4. Speicherdauer

- Konto: bis du es löschst. Dann werden Passwort, E-Mail-Adresse, „zuletzt online“, Token, Codes,
  Freundschaften, Blockierungen und Abzeichen gelöscht, und dein Benutzername wird durch „deleted-“ und eine
  Nummer ersetzt. Der Kontodatensatz bleibt unter dieser Nummer (pseudonymisiert) mit dem
  Registrierungszeitpunkt bestehen, damit Spiele, Uhren, Daueraufträge und Meldungen, die auf ihn
  verweisen, gültig bleiben. Die Änderungen der Elo-Zahl deiner gewerteten Spiele bleiben als
  Spielverlauf der anderen Spieler unter dieser Nummer erhalten; in der Liste „Top-Spieler“
  erscheinst du nicht mehr. Deine Plätze in laufenden Spielen übernimmt der Computer.
- E-Mail-Bestätigungscodes: 15 Minuten gültig; gelöscht spätestens zwei Stunden nach ihrer Erstellung.
- Anmelde-Token: 15 Minuten (Zugriff) bzw. 30 Tage (Erneuerung); abgelaufene Token werden spätestens
  eine Stunde nach ihrem Ablauf gelöscht.
- Push-Token: bis du dich in der App abmeldest, dein Konto löschst oder Firebase den Token als
  ungültig meldet. Meldet sich auf dem Gerät ein anderes Konto an, gehört der Token danach diesem.
- Chatnachrichten: 90 Tage, danach automatisch gelöscht. Nachrichten eines gelöschten Kontos
  erscheinen bis dahin als „deleted user“.
- Spiele und Spielstände: solange der Dienst betrieben wird, damit Mitspieler ihre Partien
  fortsetzen und ansehen können; nach Löschung deines Kontos ohne deinen Benutzernamen. Den letzten
  Spielstand eines beendeten Spiels (für die Wiedergabe) löschen wir 365 Tage nach Spielende,
  spätestens einen Tag danach; die Spieldaten und das Ergebnis für die Statistik bleiben.
- IP-Adressen: höchstens 7 Tage (Anfragebegrenzung im Arbeitsspeicher höchstens einen Tag;
  Server-Protokolle [Hinweis für den Betreiber: Protokollrotation auf höchstens 7 Tage einrichten]).
- Meldungen: [Frist; Hinweis für den Betreiber: der Server löscht Meldungen derzeit nicht
  automatisch, sie müssen nach dieser Frist von Hand gelöscht werden].

## 5. Empfänger

Wir verkaufen und teilen deine Daten nicht. Daten erhalten nur:

- Mitspieler: dein Benutzername, deine Spielzüge und Chatnachrichten in den Spielen und Kanälen, in
  denen du bist; Freunde zusätzlich, ob du online bist, wann du zuletzt online warst und welches
  Spiel du gerade spielst, und eure privaten Nachrichten.
- Alle angemeldeten Nutzer: deine Nachrichten im Lobby-Chat sowie öffentliche Spiele in der Lobby
  mit den Benutzernamen auf ihren Plätzen (bei Spielen „nur Freunde“ die Freunde des Erstellers);
  dein Profil mit Profilbild, Statustext und Spielstatistik (gespielte, gewonnene, verlorene,
  aufgegebene und abgebrochene Spiele, meistgespielte Karte und Nation), deine Abzeichen mit Datum,
  deine Siege je Nation (für die Freischaltung von Profilbildern), deine Siege je Szenario der
  Kartenerfolge und die Nationen, mit denen du dort gewonnen hast, deine Elo-Zahl und die
  Zahl deiner gewerteten Spiele sowie deine letzten beendeten Spiele mit den Änderungen der
  Elo-Zahl aller Spieler, die sie nach denselben Regeln sehen dürfen, auch als Wiedergabe; in der
  Liste „Top-Spieler“ stehen Benutzername, Profilbild und Elo-Zahl, sobald du mindestens 5
  gewertete Spiele hast.
- Zuschauer: Spiele, die Zuschauer erlauben, können angemeldete Nutzer mit etwas Verzögerung
  ansehen, mit dem Spielstand, den Benutzernamen der Spieler und dem Zuschauer-Chat.
- Wer Meldungen bearbeitet ([Betreiber oder beauftragte Moderatoren]): die Meldung, die gemeldete
  Chatnachricht und die Benutzernamen der meldenden und der gemeldeten Person.
- Der Hosting-Anbieter: [Name des Hosting-Anbieters], der den Server für uns betreibt
  (Auftragsverarbeitung nach Art. 28 DSGVO).
- Google (Firebase Cloud Messaging; Google Ireland Limited, Google LLC), nur in der Android-App mit
  Online-Spiel: der Push-Token, Titel und Text der Benachrichtigung (Spielname, Nation, Art
  der Entscheidung, bei manchen Entscheidungen das Gebiet) und die Spiel-ID (Auftragsverarbeitung nach Art. 28 DSGVO). Dabei können Daten in
  die USA übertragen werden (Angemessenheitsbeschluss EU-US Data Privacy Framework).
  [Hinweis für den Betreiber: die Firebase-Datenverarbeitungsbedingungen in der Firebase-Konsole
  annehmen.]
- Der E-Mail-Versanddienst: [Name des SMTP-Anbieters], nur wenn du eine E-Mail-Adresse angibst
  oder ein Passwort zurücksetzt (Auftragsverarbeitung nach Art. 28 DSGVO). Er erhält deine
  E-Mail-Adresse und die Mail mit dem Code bzw. dem Hinweis auf eine Kampfentscheidung (Spielname,
  Nation, Art der Entscheidung); E-Mails werden im Klartext übertragen und können
  unterwegs von den beteiligten Mail-Servern gelesen werden.

Karten lädt der Server von GitHub herunter; dabei werden keine Daten über dich übertragen.

## 6. Serverstandort

Der Server steht in [Land/Rechenzentrum]. [Falls außerhalb der EU: Angabe der Garantien nach
Art. 44 ff. DSGVO.]

## 7. Mindestalter

Online-Konten sind ab 16 Jahren. Bei der Registrierung bestätigst du, dass du mindestens 16 Jahre
alt bist.

## 8. Keine automatisierte Entscheidungsfindung

Wir treffen keine automatisierten Entscheidungen im Sinne von Art. 22 DSGVO und erstellen keine
Profile. Läuft deine Bedenkzeit ab, übernimmt nach den Regeln des Spiels der Computer deine Nation;
das ist eine Spielregel, die der Ersteller des Spiels einstellt.

## 9. Deine Rechte

Du hast das Recht auf

- Auskunft über deine gespeicherten Daten (Art. 15 DSGVO),
- Berichtigung (Art. 16 DSGVO),
- Löschung (Art. 17 DSGVO): jederzeit selbst in der App unter „Delete account“,
- Einschränkung der Verarbeitung (Art. 18 DSGVO),
- Datenübertragbarkeit (Art. 20 DSGVO).

Schreib uns dafür an [E-Mail-Adresse]. Du kannst dich außerdem bei einer
Datenschutz-Aufsichtsbehörde beschweren, zum Beispiel bei der Behörde deines Wohnorts oder bei
[zuständige Aufsichtsbehörde].

### Widerspruchsrecht (Art. 21 DSGVO)

Du kannst jederzeit aus Gründen, die sich aus deiner besonderen Situation ergeben, der Verarbeitung
deiner Daten widersprechen, die wir auf unser berechtigtes Interesse stützen (Art. 6 Abs. 1 lit. f
DSGVO: Anfragebegrenzung, Server-Protokolle, Meldungen). Wir verarbeiten sie dann nicht mehr, es sei
denn, wir können zwingende schutzwürdige Gründe nachweisen, die deine Interessen überwiegen, oder
die Verarbeitung dient der Geltendmachung, Ausübung oder Verteidigung von Rechtsansprüchen. Ein
formloser Widerspruch an [E-Mail-Adresse] genügt.

## 10. Änderungen

Wir passen diese Erklärung an, wenn sich der Dienst oder die Rechtslage ändert. Die jeweils
aktuelle Fassung steht in der App und unter [Adresse der Website]. Bei wesentlichen Änderungen
informieren wir dich in der App.
