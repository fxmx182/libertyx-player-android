# Amazon Appstore — LibertyX Player (Fire TV)

A Amazon Appstore hoje só existe nos aparelhos da própria Amazon: Fire TV e
tablets Fire. A dos celulares Android comuns fechou em 20/08/2025. O Player vai
para lá **só como app de TV**, com o mesmo pacote `com.mauricio.libertyx`. Um
APK só serve a todos os Fire TV: Fire TV Stick (todas as gerações com Fire OS),
Stick 4K/4K Max, Cube e as TVs com Fire TV de fábrica.

| APK | Aparelho | Pro |
|---|---|---|
| `dist/amazon/LibertyXPlayer-FireTV.apk` | Fire TV (Fire OS 6, 7 e 8) | 7 dias grátis, depois compra única pela **Amazon** (`libertyx_pro`, US$ 4,99) |

Gerar: `./gradlew assembleAmazon`. Assinado com `keystore/viper.jks` (a Amazon
reassina), Pro travado, sem a chave LibertyX e sem a biblioteca da Play. A
chave pública do app está em `app/src/amazon/assets/AppstoreAuthenticationKey.pem`;
sem ela o build para.

- `minSdk 24`: entram Fire OS 6 (Android 7.1), 7 (Android 9) e 8 (Android 11).
- **Vega OS** (o sistema novo da Amazon, sem Android, do Fire TV Stick 4K
  Select) não roda app Android: esses aparelhos ficam de fora sozinhos.
- Quem instalou o APK do GitHub no Fire TV precisa desinstalar antes de
  instalar pela loja: a assinatura da Amazon é outra.

## Estado no console (2026-10-20)

- App `LibertyX Player`
  (amzn1.devportal.mobileapp.d4b0b2f98740417989adba4961ce6642), categoria
  Photo & Video › Video Player, suporte libertyx.suporte@gmail.com, idiomas
  en/pt/es.
- IAP `libertyx_pro` (Entitlement) "Ready to Submit": US$ 4,99 base (Japão
  ¥791, Europa €4,45, Brasil convertido pela Amazon).
- App grátis; textos en-US, pt-BR, es-ES (mando/ordenadores) e es-MX (control
  remoto/computadoras) completos; ícones 512/114, ícone Fire TV, 3 capturas
  `tv-*` e fundo enviados.
- APK1 FireTV 4210722 (DRM: No) → só os 91 Fire TV com Fire OS; a Amazon tinha
  marcado 12 tablets e 2 "Automotive", desmarcados à mão. 18+, todos os países,
  classificação tudo "None/No", não coleta dados, política de privacidade do
  repositório, instruções para o revisor preenchidas. A Amazon exige 3 capturas
  de tablet mesmo sem tablet: foram as `tablet-en-{1,3,4}` reduzidas a 1920×1080.
- **Tablet Fire (decidido em 2026-10-20: pago como na TV), para mandar como
  atualização depois da aprovação:** `dist/amazon/LibertyXPlayer-FireTablet.apk`
  (variante de celular, arm64+armv7, final 1). No console: adicionar como APK2,
  deixar só os tablets Fire nele (e conferir que o APK1 continua só com Fire TV),
  responder "touch: Yes", trocar as capturas de tablet pelas de verdade.
- **Enviado em 2026-10-20** (com o OK do dono): IAP "Submit to Test and Live" →
  *Approved*; export compliance marcada; app *Submitted*, previsão da Amazon
  para entrar na loja: 25/10/2026.

## Recusa, contestação e reenvio (out/2026)

- 23/10: recusado por Content Policy (título "LibertyX" parecido com o app de
  bitcoin da Moon, Inc.). Contestação no case 22773801411, citando o Folder
  aprovado com a mesma marca → **25/10: nome liberado.**
- Mas o revisor parou no teste funcional: "o app exige login e não há
  credenciais de teste". A Fire TV dele não tem vídeo, e o único caminho era o
  servidor SMB (usuário/senha). Resposta: commit c681dbf — tela vazia com o
  **vídeo de demonstração Sintel** (Blender, CC-BY, MKV 1080p com AC-3 e 10
  legendas) tocando de download.blender.org, e texto dizendo que não há conta.
- Commit 403655c: a demonstração também aparece na tela de permissão negada do
  celular/tablet (antes era um beco só com "Permitir").
- Reenvio, como no Folder: APK1 FireTV 4275182 no lugar do 4210722 (só os Fire
  TV) + APK2 FireTablet 4275181 (só os tablets Fire), com as instruções abaixo.

- **Reenviado em 2026-10-25** (24/10 no horário dos EUA) com o OK do dono: *Submitted*, previsão 29/10/2026.
- **LIVE em 2026-10-27 23:33 PDT (28/10 15:33 no Japão)**: Functionality 104/104 aparelhos. Página: https://www.amazon.com/dp/B0HNNXC4JG (ASIN B0HNNXC4JG). Logo depois da liberação aparecia no tablet e no site amazon.com (com "Deliver to FireTVStick" e "Get App"), mas ainda não na loja da Fire TV nem na busca da amazon.co.jp: atraso normal do catálogo. Atalho: "Get App" no site entrega na Fire TV.

- **Atualização enviada em 2026-11-03** (03/11 08:26 GMT, com o OK do dono): APK1 FireTV 4411242 + APK2 FireTablet 4411241 (commit 088279f: abas na TV, Jellyfin/Emby, atraso da legenda, busca pelo controle). Novidades em en-US, pt-BR, es-ES e es-MX (o console exige todos os idiomas da página). Instruções ao revisor: o Pro agora fica na aba Settings (o atalho no rodapé da tela inicial saiu). "Any significant changes to report?" = None. *Submitted*, previsão 08/11/2026 00:50 PST.
  - Os APKs (114 MB) não passam pela extensão do Chrome (limite de 10 MB): o dono troca no console pelo compartilhamento `\\192.168.50.88\DATA\Claudinho\LibertyX\player-android\dist\amazon`.
  - Ficou *Live* até 05/11.
- **Atualização enviada em 2026-11-05** (com o OK do dono): APK1 FireTV 4433892 + APK2 FireTablet 4433891 (commit 1f502d2: o índice de fontes do VLC é montado em segundo plano na primeira abertura; antes, o primeiro vídeo depois de instalar esperava até 1 minuto). Novidades nos 4 idiomas e, junto, os textos novos da página (descrição curta, longa, destaques e palavras-chave com Jellyfin/Emby/WebDAV) em en-US, pt-BR, es-ES e es-MX — preenchidos por script na página (setter nativo do textarea + evento input). A divisão de aparelhos NÃO zerou desta vez (91 Fire TV / 12 tablets). A Amazon separa as palavras-chave em termos de uma palavra. *Submitted*, previsão 09/11/2026 14:30 PST.
- **Atualização enviada em 2026-11-09** (o dono apertou Submit): APK1 FireTV 4483362 + APK2 FireTablet 4483361 (commit 7dab292: Jellyfin completo, página do vídeo na TV, HDR, legenda − e +, dock editável, deitado sem corte, senhas não somem). O Submit ficou apagado até as novidades estarem preenchidas nos 4 idiomas (en-US, es-ES, es-MX, pt-BR); texto em `dist/NOVIDADES.txt`. Aparelhos: 92 Fire TV / 12 tablets.

- **2026-11-14: o Sintel saiu** (pedido do dono, nos três Players). Sem ele, a Fire TV vazia do revisor não tem vídeo próprio: as instruções agora mandam usar o **servidor público de demonstração do Jellyfin** (`https://demo.jellyfin.org/stable`, usuário `demo`, senha vazia — testado no emulador viper-tv com o APK da Amazon: entrou e tocou). Risco baixo: segundo o dono, o Sintel nem tocava (dava erro ao abrir) e as atualizações passaram assim mesmo.

- **2026-11-14 (pausado):** versão nova criada no console (Upcoming Version) com as notas "sem o vídeo de demonstração" nos 4 idiomas, salvas. **Falta:** o dono trocar APK1 por `dist/amazon/LibertyXPlayer-FireTV.apk` (4570432) e APK2 por `LibertyXPlayer-FireTablet.apk` (4570431) com Replace; depois conferir os aparelhos (Fire TV no APK1, só tablets no APK2), atualizar as instruções do revisor (texto abaixo, com o Jellyfin de demonstração) e enviar. Play do Player: ainda nada.

Instruções para o revisor (o campo estava **vazio** no console ao reabrir em 25/10 — o texto de 20/10 talvez nunca tenha sido salvo; agora salvo e conferido):

```
NO LOGIN, NO ACCOUNT. LibertyX Player has no user accounts and no sign-in of any kind. Nothing needs credentials to be tested.

1) Open the app. Android asks for access to videos: allow it (or not; both work).
2) If the Fire TV has no videos of its own, the home screen shows "No videos on this device". To get something to play, use the PUBLIC JELLYFIN DEMO SERVER run by the Jellyfin project (free, open movies, public test account published on jellyfin.org):
   - On the home screen select "Connect to your home server" > Add server > Jellyfin.
   - Address: https://demo.jellyfin.org/stable
   - User: demo   Password: (leave empty)
   - Save. The server appears in the list: select "demo.jellyfin.org/stable" to browse its movies and series (Continue Watching, Libraries), open a title and select "From start" to play. Later it is under "Manage servers" on the home screen.
3) In the player: LEFT/RIGHT seek; focus the progress bar for fine seeking; the MENU button opens audio track, subtitles, speed, aspect ratio and the other player tools. BACK closes the player.
4) Videos on the device or a USB drive appear in rows on the home screen.

Fire tablets (APK2): same app with a touch interface; add the same Jellyfin demo server under Files > Add server (+) > Jellyfin. Tap the video to show the player controls.

About "Connect to your home server": it lets users play files from their own PC, NAS or media server (SMB, WebDAV, Jellyfin, Emby). The user name/password fields there are the user's own server credentials, not an account with us. The Jellyfin demo above is the easiest way to test it without a server of your own.

In-app purchase: entitlement SKU libertyx_pro ("LibertyX Pro", US$ 4.99, one-time). The first 7 days after install are a free trial with all Pro features unlocked. Purchase screen: on Fire TV, the Settings tab at the top of the screen > "LibertyX Pro"; on Fire tablets, Settings in the bottom bar > "LibertyX Pro". "Restore purchase" re-checks. The app collects no data and shows no ads.
```

## Testar a compra antes

O App Tester (sandbox) só funciona com build depurável; o log do SDK diz
"Production Mode: Release build". Para testar o APK de verdade, usar o **Live
App Testing** do console: os testadores recebem o app pela própria Appstore e
compram sem cobrança. No emulador daqui, sem Appstore, a tela do Pro mostra
"Comprar na Amazon Appstore", que é o comportamento certo num aparelho sem a loja.

---

## Textos da página

### English (US), o padrão

**Short description:**
Plays everything on your Fire TV (MKV, HEVC, 4K and HDR, subtitles) straight from your PC, NAS, Jellyfin or Emby server on the home network, without copying anything. Made for the remote. The player is free; LibertyX Pro (home servers, sleep timer, frame capture) is free for 7 days, then a one-time purchase.

**Long description:**

```
The video player for people with a real collection, now on your Fire TV.

LibertyX opens almost any file — MKV, MP4, AVI, HEVC/H.265, 4K and HDR, AC-3 and DTS audio, SRT, ASS and PGS subtitles — with no converting. And it plays straight from your PC, NAS, Jellyfin or Emby server, without copying anything to the TV.

★ MADE FOR THE REMOTE
A gold focus you can read from across the room. Home, Files, Search and Settings tabs at the top; your servers come first on the home screen, then your videos in rows. The MENU button opens the player tools, and holding OK on a card shows more options.

★ YOUR HOME SERVERS
• SMB (Windows, macOS, Linux, Samba, NAS): LibertyX finds them on your network by itself. Sign in with a user name and password, or as a guest.
• Jellyfin and Emby: Continue Watching, Next Up and Recently Added, series and movies with cover art, and progress shared with the server. A finished episode gives way to the next one; hold OK to mark an item as played.
• WebDAV and Nextcloud.

★ SMOOTH SEEKING
◀ ▶ add up and jump once, without stutter; focus the bar for fine, frame-accurate scrubbing.

★ TOOLS AT HAND
Audio track, subtitles (subtitle files next to the video load by themselves), subtitle delay, speed from 0.5× to 6×, aspect ratio, repeat, shuffle, night mode and resume where you left off.

★ LIBERTYX PRO — 7 DAYS FREE
The player is free forever. Pro is a one-time purchase, no subscription, that unlocks:
• Home servers: SMB (PC, NAS, network drives), Jellyfin, Emby and WebDAV
• Sleep timer
• Full-resolution frame capture
For the first 7 days everything is unlocked so you can try it. The purchase works on every Fire TV with your Amazon account.

★ NO ADS, NO TRACKING
LibertyX collects no data at all. Your videos and passwords stay on your device.

In English, Portuguese and Spanish.

Your media. Your freedom.

Demo footage: open movies by the Blender Foundation (CC-BY).
```

**Feature bullets:**
```
Plays MKV, MP4, HEVC/H.265, 4K and HDR, AC-3/DTS audio, SRT/ASS/PGS subtitles
Streams straight from SMB, Jellyfin, Emby and WebDAV servers, without copying
Jellyfin and Emby: Continue Watching, Next Up, cover art and played status
Made for the remote: tabs, gold focus, smooth seeking with ◀ ▶
Subtitle delay, audio and subtitle tracks, speed 0.5× to 6×, resume
No ads, no tracking
```

**Keywords:** video player, mkv, hevc, 4k, hdr, subtitles, smb, samba, nas, jellyfin, emby, webdav, nextcloud, network, media player, fire tv

### Português (Brasil)

**Descrição curta:**
Toca tudo na sua Fire TV (MKV, HEVC, 4K e HDR, legendas) direto do seu PC, NAS ou servidor Jellyfin ou Emby na rede de casa, sem copiar nada. Feito para o controle remoto. O player é grátis; o LibertyX Pro (servidores de casa, timer para dormir, captura de quadro) é grátis por 7 dias, depois uma compra única.

**Descrição longa:**

```
O player de vídeo para quem tem uma coleção de verdade, agora na sua Fire TV.

O LibertyX abre praticamente qualquer arquivo — MKV, MP4, AVI, HEVC/H.265, 4K e HDR, áudio AC-3 e DTS, legendas SRT, ASS e PGS — sem converter nada. E toca direto do seu PC, NAS ou servidor Jellyfin ou Emby, sem copiar nada para a TV.

★ FEITO PARA O CONTROLE REMOTO
Foco dourado que se lê do sofá. Abas Início, Arquivos, Procurar e Ajustes no alto; os seus servidores vêm primeiro na tela inicial, depois os seus vídeos em faixas. O botão MENU abre as ferramentas do player, e segurar OK num cartão mostra mais opções.

★ OS SERVIDORES DA SUA CASA
• SMB (Windows, macOS, Linux, Samba, NAS): o LibertyX acha sozinho na sua rede. Entre com usuário e senha, ou como convidado.
• Jellyfin e Emby: Continuar assistindo, A seguir e Adicionados recentemente, séries e filmes com capa, e o progresso junto com o servidor. O episódio terminado dá lugar ao próximo; segure OK para marcar como já reproduzido.
• WebDAV e Nextcloud.

★ BUSCA SUAVE
◀ ▶ somam e o vídeo pula uma vez só, sem trancos; com a barra em foco, a rolagem é fina, quadro a quadro.

★ FERRAMENTAS À MÃO
Faixa de áudio, legendas (as legendas ao lado do vídeo entram sozinhas), atraso da legenda, velocidade de 0,5× a 6×, proporção, repetir, aleatório, modo noturno e retomar de onde parou.

★ LIBERTYX PRO — 7 DIAS GRÁTIS
O player é grátis para sempre. O Pro é uma compra única, sem assinatura, que libera:
• Servidores de casa: SMB (PC, NAS, HD na rede), Jellyfin, Emby e WebDAV
• Timer para dormir
• Captura de quadro na resolução máxima
Nos primeiros 7 dias tudo vem liberado para você experimentar. A compra vale em todas as Fire TV da sua conta Amazon.

★ SEM ANÚNCIOS, SEM RASTREAMENTO
O LibertyX não coleta nenhum dado. Seus vídeos e suas senhas ficam no seu aparelho.

Em português, inglês e espanhol.

Sua mídia. Sua liberdade.

Imagens de demonstração: filmes abertos da Blender Foundation (CC-BY).
```

**Destaques:**
```
Toca MKV, MP4, HEVC/H.265, 4K e HDR, áudio AC-3/DTS, legendas SRT/ASS/PGS
Toca direto de servidores SMB, Jellyfin, Emby e WebDAV, sem copiar nada
Jellyfin e Emby: Continuar assistindo, A seguir, capas e marcação de já visto
Feito para o controle remoto: abas, foco dourado, busca suave com ◀ ▶
Atraso da legenda, faixas de áudio e legenda, velocidade de 0,5× a 6×, retoma de onde parou
Sem anúncios, sem rastreamento
```

**Palavras-chave:** player de vídeo, mkv, hevc, 4k, hdr, legendas, smb, samba, nas, jellyfin, emby, webdav, nextcloud, rede, reprodutor, fire tv

### Español (es-ES y es-MX)

Mesmo texto nos dois; no es-ES trocar "control remoto" por "mando", "presionado" por "pulsado", "video" por "vídeo" e "Agregados" por "Añadidos".

**Descripción corta:**
Lo reproduce todo en tu Fire TV (MKV, HEVC, 4K y HDR, subtítulos) directo desde tu PC, NAS o servidor Jellyfin o Emby en la red de casa, sin copiar nada. Hecho para el control remoto. El reproductor es gratis; LibertyX Pro (servidores de casa, temporizador, captura de fotograma) es gratis 7 días, después una compra única.

**Descripción larga:**

```
El reproductor de video para quien tiene una colección de verdad, ahora en tu Fire TV.

LibertyX abre casi cualquier archivo — MKV, MP4, AVI, HEVC/H.265, 4K y HDR, audio AC-3 y DTS, subtítulos SRT, ASS y PGS — sin convertir nada. Y reproduce directo desde tu PC, NAS o servidor Jellyfin o Emby, sin copiar nada a la TV.

★ HECHO PARA EL CONTROL REMOTO
Un foco dorado que se lee desde el sofá. Pestañas Inicio, Archivos, Buscar y Ajustes arriba; tus servidores aparecen primero en la pantalla de inicio, después tus videos en filas. El botón MENU abre las herramientas del reproductor, y mantener presionado OK en una tarjeta muestra más opciones.

★ LOS SERVIDORES DE TU CASA
• SMB (Windows, macOS, Linux, Samba, NAS): LibertyX los encuentra solo en tu red. Entra con usuario y contraseña, o como invitado.
• Jellyfin y Emby: Seguir viendo, A continuación y Agregados recientemente, series y películas con portada, y el progreso compartido con el servidor. El episodio terminado da paso al siguiente; mantén presionado OK para marcarlo como ya reproducido.
• WebDAV y Nextcloud.

★ BÚSQUEDA SUAVE
◀ ▶ se suman y el video salta una sola vez, sin tirones; con la barra enfocada, el avance es fino, fotograma a fotograma.

★ HERRAMIENTAS A MANO
Pista de audio, subtítulos (los subtítulos junto al video se cargan solos), retraso de subtítulos, velocidad de 0,5× a 6×, proporción, repetir, aleatorio, modo nocturno y continuar donde lo dejaste.

★ LIBERTYX PRO — 7 DÍAS GRATIS
El reproductor es gratis para siempre. Pro es una compra única, sin suscripción, que desbloquea:
• Servidores de casa: SMB (PC, NAS, discos en red), Jellyfin, Emby y WebDAV
• Temporizador para dormir
• Captura de fotograma a resolución máxima
Durante los primeros 7 días todo está desbloqueado para que lo pruebes. La compra vale en todos los Fire TV de tu cuenta de Amazon.

★ SIN ANUNCIOS NI RASTREO
LibertyX no recopila ningún dato. Tus videos y tus contraseñas se quedan en tu dispositivo.

En español, inglés y portugués.

Tus videos. Tu libertad.

Imágenes de demostración: películas abiertas de la Blender Foundation (CC-BY).
```

**Viñetas:**
```
Reproduce MKV, MP4, HEVC/H.265, 4K y HDR, audio AC-3/DTS, subtítulos SRT/ASS/PGS
Reproduce directo desde servidores SMB, Jellyfin, Emby y WebDAV, sin copiar nada
Jellyfin y Emby: Seguir viendo, A continuación, portadas y marca de ya visto
Hecho para el control remoto: pestañas, foco dorado, búsqueda suave con ◀ ▶
Retraso de subtítulos, pistas de audio y subtítulos, velocidad de 0,5× a 6×, continúa donde lo dejaste
Sin anuncios ni rastreo
```

**Palabras clave:** reproductor de video, mkv, hevc, 4k, hdr, subtítulos, smb, samba, nas, jellyfin, emby, webdav, nextcloud, red, fire tv

## Imagens (`imagens/` e `imagens/amazon/`)

- Ícones: `amazon/icone-512.png` e `amazon/icone-114.png`.
- Fire TV: ícone `amazon/firetv-icone-1280x720.png` (arte dentro da área segura
  de 882×448) e fundo `amazon/firetv-fundo-1920x1080.jpg`. Tudo gerado por
  `Scripts/icone.py`.
- Capturas 1920×1080: `tv-1-inicio.jpg`, `tv-2-biblioteca.jpg`, `tv-3-player.jpg`.
- Política de privacidade:
  `https://github.com/fxmx182/libertyx-player-android/blob/main/PRIVACY.md`.
