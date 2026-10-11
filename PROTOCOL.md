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
- Conexão nova para par já conectado é **recusada** (`bye{reason:"duplicate"}`).
- Corrida (discagem simultânea): o nó **maior** que receber `hello` **aborta a própria
  discagem em curso** — a conexão iniciada pelo menor sempre vence. Se mesmo assim duas
  conexões vivas coexistirem, cada lado fecha deterministicamente a que **não** foi
  iniciada pelo menor (o menor recusa a entrada com `bye duplicate`; o maior troca).

## 4. Pareamento (primeira vez)

Qualquer nó abre **"Parear novo dispositivo"**: gera credencial de uso único (**2 min**):
`token` (hex, p/ QR) + `código` de 6 dígitos (p/ manual, mesmo prazo). Mostra:

- QR JSON: `{ ip, port, token, fingerprint, deviceId }` (`ip` = IP LAN RFC1918, nunca VPN/virtual).
- Manual (obrigatório p/ PC↔PC): `IP:porta` + código de 6 dígitos; o outro nó digita em
  **"Conectar manualmente"**.

Fluxo (joiner = quem escaneou/digitou; host = quem mostrou):

1. Joiner conecta em `wss://ip:porta/ws`, valida o certificado contra o `fingerprint` (pinning).
2. Joiner → `hello{ token|code, deviceId, name, profile:{name,photoHash}, fingerprint }`
   (`fingerprint` = cert do joiner).
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
| `hello` | dialer→listener | `{ token? , code?, deviceId, name, profile:{name,photoHash}, fingerprint, port }` (`port` = escuta do remetente, p/ discar de volta) |
| `auth` | listener→dialer | `{ nonce }` |
| `auth` | dialer→listener | `{ hmac, nonce }` |
| `auth_ok` | listener→dialer | `{ hmac }` (reconexão) ou `{ secret, deviceId, profile }` (pareamento) |
| `ping` / `pong` | ambos | `{}` / `{ echo }` — 15 s; 3 faltas = caída |
| `bye` | ambos | `{ reason }`: `not-paired\|bad-hmac\|duplicate\|rate-limited\|going-away` |
| `profile` / `profile_update` | ambos | `{ name, photoHash }` |
| `profile_photo_request` | ambos | `{}` |
| `profile_photo` | ambos | `{ photoHash, mime, dataBase64 }` |

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
