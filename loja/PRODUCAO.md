# Formulário de acesso à produção (Play Console)

Respostas para o formulário que a Play abre depois dos 14 dias de teste fechado.
Base: o relatório da Testers Community (25/11/2026), corrigido para dizer só o
que é verdade. O modelo deles afirma mudanças que não foram feitas — "pesquisas",
"sessões de experiência", "walkthrough adicionado por causa do teste" — e o
Google compara o formulário com o app e com o que aconteceu no teste. Resposta
genérica ou falsa é o motivo mais comum de recusa.

Ajuste o item 1 se também chamou amigos ou família.

---

**1) How did you recruit users for your closed test?**

```
I used a paid testing provider (Testers Community) to reach the required number of testers on a variety of devices and Android versions. In parallel I tested every build myself on my own phone, tablet and Android TV, against my own home servers (SMB, Jellyfin, Emby and WebDAV), which is the real use case of the app.
```

**2) How easy was it to recruit testers for your app?**

`Neither easy nor difficult` (ou a opção que for verdade para você)

**3) Describe the engagement you received from testers during your closed test**

```
Testers installed and used the app across several devices and Android versions during the 14 days. They reported no crashes or functional bugs; their written report focused on discoverability: a rating shortcut, store listing text and screenshots. Most of the bug reports during the period came from my own daily use of the app with real media servers.
```

**4) Provide a summary of the feedback that you received from testers. Include how you collected the feedback.**

```
Feedback came through the provider's written report and through Play's closed-testing feedback channel. Testers found no crashes or bugs and said all features worked as intended. Suggestions: a "Rate the app" option in Settings, a richer store description and feature-focused screenshots, and onboarding help for new users.
```

**5) Who is the intended audience for your app?**

```
Adults who keep their own video collection: movies and series on the phone, on a PC or NAS at home (SMB), or on a Jellyfin, Emby or WebDAV/Nextcloud server. It is for people who want one player that opens any format (MKV, HEVC, 4K/HDR, AC-3/DTS, ASS/PGS subtitles) on phone, tablet and Android TV, without ads or tracking.
```

**6) Describe how your app provides value to the users.**

```
LibertyX Player plays almost any video file without conversion and streams directly from the user's own home servers, so the collection does not need to be copied to the device. It shows Continue watching / Next up from Jellyfin and Emby with progress synced, loads side-by-side subtitles even over the network, offers frame-accurate seeking and familiar gestures, and works with the remote on Android TV. It has no ads, collects no data and keeps server passwords encrypted on the device.
```

**7) How many installs do you expect your app to have in your first year?**

`1K – 10K` (estimativa realista para um app novo sem divulgação paga; a
Testers Community sugeriu 10K–100K, escolha a que achar certa)

**8) What changes did you make to your app based on what you learned during your closed test?**

```
- Added a "Rate LibertyX" option in Settings that opens the store page.
- Emby playback fixed (direct play and transcoding).
- Network recordings in MPEG-TS now show duration and allow seeking.
- Server passwords are kept after updating or reinstalling the app.
- Pinch zoom now stays where the user releases it.
- HDR output on phones/tablets with HDR screens and on Android TV.
- Android TV: Jellyfin-style movie and episode pages, subtitle size control, smoother focus on rows.
- First video opens faster (no wait for VLC's source index).
- Store listing updated with server features (Jellyfin, Emby, WebDAV) and the new navigation.
The app already had a first-run guide (shown on first launch and reachable from Settings › View guide), so no new walkthrough was needed.
```

**9) How did you decide that your app is ready for production?**

```
The closed test ran with no crashes or bugs reported by testers on any device or Android version, and the core flows — local files, SMB, Jellyfin, Emby, WebDAV, subtitles, background audio and Android TV — have been used daily on real devices for weeks without regressions.
```

**10) What did you do differently this time?**

Só aparece se for uma segunda tentativa. Se aparecer:

```
I answered with what actually changed during the test, added the rating shortcut testers asked for, and fixed the playback issues found with Emby and network recordings before applying.
```
