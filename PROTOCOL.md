# LumiLink Protocol v2 (P2P simétrico) — PC↔PC, PC↔Android, Android↔Android

Todo nó é **servidor e cliente** ao mesmo tempo. Protocolo idêntico nas 3 combinações.
Camadas: `Transport` (server+client) · `Discovery` (mDNS) · `Pairing` · `Peers` · `Storage` · `MessageRouter` · `Profile` · `UI`.

## 1. Identidade e transporte

- `deviceId`: UUID v4 gerado na 1ª execução e guardado (nunca muda). Id legado
  (não-UUID) migra uma vez para UUID novo. Se a descoberta vir o próprio id
  anunciado por outro endpoint, registra AVISO de colisão (não deve ocorrer).
- Cada nó tem certificado TLS **autoassinado próprio**; `fingerprint` = SHA-256 hex do DER.
- Cada nó escuta `wss://0.0.0.0:<porta>/ws`. Porta padrão **aleatória** (40000–50000),
  guardada; pode ser configurada. O par sempre é contatado por `(últimoIp, porta)` ou descoberta.
- Cada nó anuncia **todos os seus IPv4** ordenados (LAN RFC1918 → Tailscale/CGNAT →
  demais → link-local; nunca loopback). O handshake é **por ID**: o discador testa cada
  IP candidato com fail-fast e fixa o que autenticar (log `conectado via ip:porta`).
  Vale para qualquer rede roteável entre os nós: Wi-Fi/LAN, Tailscale, **PAN Bluetooth**
  (tethering Bluetooth entrega IP — o LumiLink conecta sobre ele sem pilha BT própria).
- Frames: JSON texto `{ "type": "...", "id": "...", "payload": { } }`, `id` = 16 hex aleatórios.

## 2. Descoberta (mDNS)

- Serviço: `_lumilink._tcp.local`. Instância: `LumiLink-<deviceId>` (o deviceId **na instância**
  é a identidade canônica na descoberta — funciona em todas as plataformas).
- TXT (onde suportado): `deviceId=<uuid>`, `name=<nome>`. Porta via SRV; IP via A.
- Nós descobrem pares **pareados** pelo `deviceId` e aplicam a regra de papel (§3).

## 3. Papéis por conexão (anti-duplicidade)

- Uma conexão ativa por par. Quando dois nós pareados se descobrem/conhecem:
  - Só o nó de **`deviceId` menor (ordem lexicográfica)** inicia a conexão (disco imediatamente).
  - O de `deviceId` **maior** aguarda entrada; se após **10 s** vendo o par (mDNS) não houver
    conexão, ele pode discar (cobre o menor offline).
  - O maior que receber `hello` com discagem própria em curso **aborta a própria**
    (a do menor vence).
- Handshake NUNCA é recusado por duplicidade: ambos os lados autenticam e depois mantêm
  deterministicamente a conexão **discada pelo menor** (a outra é fechada). Sem isso, um
  lado com link meio-morto recusava para sempre e o par nunca reconectava (deadlock).
  `bye{reason:"duplicate"}` segue reservado.

## 4. Pareamento (primeira vez)

Qualquer nó abre **"Parear novo dispositivo"**: gera credencial de uso único (**2 min**):
`token` (hex, p/ QR) + `código` de 6 dígitos (p/ manual, mesmo prazo). Mostra:

- QR JSON: `{ ip, port, token, fingerprint, deviceId, ips[] }` (`ip` = melhor LAN;
  `ips` = todos, p/ Tailscale/PAN). Manual: `IP:porta` + código de 6 dígitos.
- Manual (obrigatório p/ PC↔PC): `IP:porta` + código de 6 dígitos; o outro nó digita em
  **"Conectar manualmente"**.

Fluxo (joiner = quem escaneou/digitou; host = quem mostrou):

1. Joiner conecta em `wss://ip:porta/ws`, valida o certificado contra o `fingerprint` (pinning).
2. Joiner → `hello{ token|code, deviceId, name, profile:{name,photoHash}, fingerprint, port, ips[] }`
   (`fingerprint` = cert do joiner; `port`/`ips` = onde o joiner escuta, p/ discar de volta).
3. Host valida credencial (uso único, 2 min), gera **segredo 32 bytes**, salva o par e responde
   `auth_ok{ secret, deviceId, profile:{name,photoHash} }`.
4. Joiner salva o par. Ambos passam a trocar `profile` (§6).

O que cada lado salva por par: `deviceId`, nome, segredo, `fingerprint` do cert do par,
último IP/porta, perfil em cache, último contato. Par antigo sem `fingerprint`: aprende no
próximo `hello` e passa a exigir.

## 5. Reconexão (autenticação mútua HMAC-SHA256)

Sem QR. Dialer = quem disca (§3); listener = quem escuta:

1. Dialer → `hello{ deviceId, name, profile, fingerprint }` (sem token).
2. Listener valida: par conhecido, `fingerprint` confere, rate-limit OK (§8).
   Responde `auth{ nonce: Nl }`.
3. Dialer → `auth{ hmac: HMAC(segredo,Nl), nonce: Nd }`.
4. Listener confere HMAC; responde `auth_ok{ hmac: HMAC(segredo,Nd) }`.
5. Dialer confere HMAC → **mútuo**. Segue `profile` (§6) e `ping/pong` (§7).

`deviceId` desconhecido → `bye{reason:"not-paired"}`. HMAC inválido → `bye{reason:"bad-hmac"}`.

## 6. Perfil

- Após `auth_ok` (e após parear), ambos enviam `profile{ name, photoHash }`
  (`photoHash` = SHA-256 dos bytes, `"empty"` sem foto).
- Hash diferente do cache → `profile_photo_request{}` → `profile_photo{ photoHash, mime, dataBase64 }`.
- Edição → `profile_update{ name, photoHash }` (mesmo mecanismo de foto).
- Validação (falha = ignora + loga, **nunca** derruba): nome ≤32, sem controle;
  foto ≤100 KB, hash confere, decodifica como imagem.

## 7. Catálogo de mensagens

| type | direção | payload |
|---|---|---|
| `hello` | dialer→listener | `{ token? , code?, deviceId, name, profile:{name,photoHash}, fingerprint, port, ips[] }` (`port`/`ips` = escuta do remetente, p/ discar de volta) |
| `auth` | listener→dialer | `{ nonce }` |
| `auth` | dialer→listener | `{ hmac, nonce }` |
| `auth_ok` | listener→dialer | `{ hmac }` (reconexão) ou `{ secret, deviceId, profile }` (pareamento) |
| `ping` / `pong` | ambos | `{}` / `{ echo }` — 15 s; 3 faltas = caída. Manual: `ping{manual:true}` — o outro lado mostra notificação ("Ping de X"); quem enviou mostra o RTT ("Pong em Nms") |
| `bye` | ambos | `{ reason }`: `not-paired\|bad-hmac\|duplicate\|rate-limited\|going-away` |
| `profile` / `profile_update` | ambos | `{ name, photoHash }` |
| `profile_photo_request` | ambos | `{}` |
| `profile_photo` | ambos | `{ photoHash, mime, dataBase64 }` |
| `clip_text` | ambos | `{ text, ts, source }` (texto ≤256 KB; `source` = deviceId de origem) |
| `clip_image` | ambos | `{ mime, dataBase64, hash, w, h }` (imagem ≤3 MB; `hash` = SHA-256) |
| `clip_file_offer` | remetente→par | `{ fileId, name, size, sha256 }` (envio explícito por botão; ≤200 MB) |
| `clip_file_accept` | par→remetente | `{ fileId, accept }` (remetente espera até 30 s) |
| `clip_file_chunk` | remetente→par | `{ fileId, seq, data }` (32 KB raw em base64, `seq` desde 0, em ordem) |
| `clip_file_done` | remetente→par | `{ fileId, sha256 }` (receptor confere e salva) |
| `media_status` | par com mídia→par | `{ title, artist, app, playing, state, ts }` (`state`: playing/paused/stopped; parado mostra ⏹ e só sai quando a sessão some; quem não tem fonte só exibe) |
| `media_pc` | PC→celular | `{ title, artist, app, art, dur, pos, playing, state, ts }` (`art` = JPEG ~300px base64, omitido se igual; vazio limpa o controle remoto) |
| `media_cmd` | celular→PC | `{ cmd: play\|pause\|toggle\|next\|prev\|seek, pos? }` (pos em ms p/ seek) |

## 6b. Clipboard (só texto e imagem)

- Cada nó monitora a área de transferência local (texto e imagem, ex. captura de tela);
  mudança → salva no **histórico SQLite** local e difunde aos pares conectados
  (se a sincronização estiver ativa). Arquivos em geral NÃO sincronizam.
- **Anti-eco:** texto/arquivo por hash SHA-256; imagem por hash perceptual (aHash),
  que sobrevive ao re-encode PNG↔JPEG entre os pares. Visto nos últimos 10 s (30 s
  p/ imagem) não é reenviado.
- Receber = salvar no histórico + aplicar na área local + notificar.
- Limites: texto 256 KB, imagem 3 MB (reduz se maior). Fora disso: ignora + loga.

Roteador por `type` (dicionário de handlers) — funções novas sem mexer na base.

## 8. Robustez

- **Backoff por par**, independente: 1 s → 30 s máx; reseta ao conectar bem.
- **Rate limit (servidor)**: 5 falhas de auth por IP em 1 min → bloqueia 5 min (`bye{reason:"rate-limited"}`).
- Rede trocada/Wi-Fi: redescoberta mDNS + último IP; queda limpa re-disca pelo backoff do par.
- Economia de bateria (Android): isenção via `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; Foreground Service.

## 9. Testes

- **Automatizado** (`pc-app/tests`): dois nós in-process (portas loopback) — pareia, conecta,
  confere auth mútua, derruba a conexão, reconecta via backoff e confere o perfil trocado.
- **Manual por combinação** (2 aparelhos na mesma rede):
  - [ ] PC↔PC: manual (código+IP:porta) → pareia → mata um lado → volta sozinho → perfil atualiza.
  - [ ] PC↔Android: QR → idem + tela desligada 5 min (notificação fixa) + boot com toggle.
  - [ ] Android↔Android: um mostra QR, outro escaneia → idem; detalhe do par mostra último IP.
  - [ ] Duplicidade: abra os dois ao mesmo tempo → **uma** conexão (sem flap).
  - [ ] Desconhecido: `hello` com id estranho → `bye not-paired`; 6 falhas seguidas → bloqueio 5 min.
