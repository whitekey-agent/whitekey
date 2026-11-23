# WhiteKey

Egress policy proxy and credential broker for AI agents. Enforces HTTP/HTTPS allowlists, supports human-in-the-loop approval, and injects encrypted credentials on egress.

## Launch

### Docker Compose

```bash
docker compose up -d
docker exec -it whitekey whitekey <command>
```

Proxy listens on `127.0.0.1:9999` (`PROXY_HOST_BIND`, `PROXY_PORT`). Data persists in `~/.local/share/whitekey` (`WHITEKEY_HOST_DATA_DIR`).

### Local (Java 25+)

```bash
./mvnw clean package -DskipTests
java --enable-native-access=ALL-UNNAMED -jar whitekey-server/target/whitekey-server-*.jar
java --enable-native-access=ALL-UNNAMED -jar whitekey-cli/target/whitekey-cli-*.jar <command>
```

## Proxy Setup

WhiteKey generates a local Root CA at `~/.local/share/whitekey/certs/whitekey_ca.crt` (also served at `GET http://127.0.0.1:1111/ca.crt`). Point your agent at the proxy and trust the CA:

```bash
CA=~/.local/share/whitekey/certs/whitekey_ca.crt
export http_proxy=http://127.0.0.1:9999 https_proxy=http://127.0.0.1:9999
export HTTP_PROXY="$http_proxy" HTTPS_PROXY="$https_proxy"
export no_proxy=127.0.0.1,localhost NO_PROXY=127.0.0.1,localhost
export SSL_CERT_FILE="$CA" NODE_EXTRA_CA_CERTS="$CA" REQUESTS_CA_BUNDLE="$CA" GIT_SSL_CAINFO="$CA"
```

For isolated sandboxes (VMs or containers), expose only `~/.local/share/whitekey/certs` (or `GET /ca.crt`) so private keys and admin tokens stay off the sandbox.

## CLI

Global flags: `whitekey [--server http://127.0.0.1:1111] [--token <token>] <command>` (also reads `WHITEKEY_SERVER_URL`, `WHITEKEY_ADMIN_TOKEN`, or `.env`). Run `whitekey <command> --help` for full options.

### Setup & Approvals

```bash
whitekey setup on --watch                     # Interactive approval loop; turns OFF on exit
whitekey setup on
whitekey setup off
whitekey setup status

whitekey approvals list
whitekey approvals watch [--interval 300]
whitekey approvals approve <uuid> [--save-rule]
whitekey approvals reject <uuid>
```

### Rules

Precedence: `DENY` > `ASK` > `ALLOW`; unmatched requests fall back to `DEFAULT_POLICY` (`DENY`). `--paths` defaults to `/*`.

```bash
whitekey rules list
whitekey rules add --name allow_github --effect ALLOW --hosts api.github.com
whitekey rules add --name limit_stripe --effect ALLOW --methods POST --hosts api.stripe.com --paths /v1/charges --condition "amount <= 5000 && currency == 'usd'"
whitekey rules add --name deny_admin --effect DENY --hosts api.example.com --paths "/admin/*"
whitekey rules remove allow_github
```

### Credentials

Built-in presets: `github`, `stripe`, `slack`, `openai`, `anthropic`.

```bash
whitekey credentials presets
whitekey credentials list
whitekey credentials add --name github-pat --preset github --secret ghp_...
whitekey credentials add --name my-api --host api.example.com --type HEADER --key X-API-Key --format "%s" --secret sk_...
whitekey credentials remove github-pat
```

### Status & History

```bash
whitekey status
whitekey history [--limit 20] [--status ALLOWED|DENIED|AWAITING_APPROVAL|EXPIRED|CANCELLED|FAILED] [--method GET] [--host api.github.com]
whitekey history clear
```

## Configuration

Set via environment variables or `.env`:

| Variable | Default | Description |
| :--- | :--- | :--- |
| `PORT` / `PROXY_PORT` | `1111` / `9999` | Admin API and agent proxy ports |
| `DEFAULT_POLICY` | `DENY` | Fallback for unmatched requests (`DENY`, `ALLOW`, `ASK`) |
| `WHITEKEY_DATA_DIR` | `~/.local/share/whitekey` | SQLite DB, token, master key, and CA directory |
| `WHITEKEY_ADMIN_TOKEN` | auto-generated | Admin bearer token override |
| `PROXY_TLS_PASSTHROUGH_HOSTS` | — | Comma-separated hosts (`*.domain` supported) tunneled as raw TCP against `CONNECT` rules |

See `whitekey-server/src/main/resources/application.yaml` and `docker-compose.yml` for bind address, timeout, and CA path overrides.
