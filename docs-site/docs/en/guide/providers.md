# AI Providers & Models

AiCode does not provide models itself — you need to connect at least one model service before you can start chatting. This page covers how to set it up.

The entry point is "Settings → AI Providers".

## Adding a Provider

Tap + at the top-right, and a bottom sheet slides up with two tabs: "Manual Setup" and "Ready to Use".

- **Manual Setup** tab: lists mainstream model services (OpenAI, Anthropic, DeepSeek, Zhipu, Qwen, etc.) in a unified list, with **Custom Provider** as the first entry (configure from scratch). Picking a built-in provider pre-fills the name, type and Base URL — just add your API Key in the edit page and it works. You can also modify any field.
- **Ready to Use** tab: free model services that need no registration are a placeholder for now and will be opened in a future release.

For custom setup, three fields are enough: **Type**, **API Key** and **Base URL**.

- **Type**: pick one of `OpenAI` / `Anthropic` / `Gemini` — it decides which protocol is used for requests. For third-party relay services, `OpenAI` is almost always the right choice.
- **API Key**: your key, like `sk-xxx`. Extra spaces or newlines around it are cleaned automatically on save.
- **Base URL**: only the API root address, **do not include `/v1` or any path after it**. Leave it empty to use the official address for the type.
- **API path**: the request path right after the Base URL, defaults to `/chat/completions` — usually you don't need to change it.
- **Name**: a recognizable name for this config, e.g. "My relay". The list auto-matches a brand icon by name (enter DeepSeek and you get DeepSeek's icon).

After setup, switch to the "Models" tab to fetch models, then you can head back to the home page and start chatting.

## Managing the Provider List

Each row shows the name, protocol type, model count and enabled status.

- Tap a row to open its edit page.
- **Swipe a row left** to reveal a red delete button.
- **Long-press and drag the name or type area** to reorder; the order survives restart.

## Multi-Key Mode

::: tip Version note
Multi-key mode is available since 1.11.0.
:::

If you hold multiple keys for the same service, turn on **Multi-Key Mode** in the "Options" of the edit page. When rate-limited or out of quota, the next key is tried automatically. The single-key input hides once enabled, and keys are managed on the "Multi-Key Management" page (the key you had entered automatically becomes the first entry, nothing is lost).

The management page has three sections:

**Key list**: add/remove keys row by row — the order is the priority. The eye icon at the top-right toggles plaintext/masked display for the whole page.

**Pickup strategy**:

- `Sequential`: always use the first key, and only switch to the next after failing up to a threshold.
- `Round robin`: new sessions start from different keys in turn, spreading quota consumption across keys.

To protect server-side prompt caching and keep a session consistent, **one key is kept within a single session** — no frequent switching that would invalidate the server cache.

**Failover**:

- `Switch threshold`: how many consecutive failures of the same key trigger a switch, default 2.
- `Cooldown`: how long a switched-away key waits before re-joining the candidate pool, default 5 minutes; can also be disabled.

Only errors that indicate **the key itself is the problem** count as failures — auth failure, insufficient permission, rate limiting, or exhausted balance/quota. Server 5xx, timeouts and network issues can't be fixed by switching keys, so they don't consume the failure count or wrongly put a healthy key into cooldown. When a switch actually happens, the error message ends with "Switched to key N/M, retry".

After an app restart, key selection returns to the first key and cooldown records are cleared.

## New Protocol Endpoints

The "Options" in the edit page lets you switch to a provider's newer protocol:

- **OpenAI type**: optionally enable the "Responses API";
- **Gemini type**: optionally enable the "Interactions API".

Keep them off by default. Enable only when you've confirmed the official direct service or relay you're using supports the corresponding new protocol.

## Other Options

- **Custom request headers**: append custom headers to all requests of this provider, fully overriding same-named defaults (e.g. setting `User-Agent` replaces the default UA). Values support `{{SESSION_ID}}` (current session id) and `{{API_KEY}}` (the key actually used this time) placeholders, substituted before sending. Only needed when a relay gateway validates specific headers.
- **Custom dashboard script**: show a balance or usage card above the input box for this provider. Scripts live in `~/.aicode/scripts/`, support Python, Bash and Node, and can be tested with "Run Test" in the edit page.
- **Script arguments**: inject extra environment variable `AICODE_KEY_<KEY>` into the dashboard script. Values support placeholders like `{{PROVIDER_API_KEY}}` (current key), `{{BASE_URL}}`, `{{MODEL}}`, substituted before the script runs.

## Model Management

Switch to the "Models" tab of the edit page.

**Fetch models**: tap "Fetch Models" to sync the available list from the server. Results are grouped by brand, collapsible and searchable. Each model shows capability tags: `Image` for image support, `Tools` for tool calls, `↑` followed by context length, `↓` followed by max output.

**Manual add**: relay model lists are sometimes incomplete — tap + and type the model name. In the edit dialog you can also customize:

- **Context window**: affects when automatic context compression kicks in.
- **Output window**: the max length of a single reply.
- **Pricing**: input / output / cached-input tiers, in USD per million tokens, used for cost statistics.
- **Capability toggles**: image input, image output, tool calls, reasoning.

Your values take priority; unfilled ones fall back to auto-detected results. If auto-detection can't match the model name, it retries after stripping common suffixes like `-thinking`, `-preview`, `-high` — so most renamed relay models are still recognized.

**Thinking effort**: the lightning icon button above the input box is always available. If the current model's supported levels can be detected, only those are listed; otherwise (model has no level info, or the relay renamed it) all levels are shown — `Off` / `Lowest` / `Low` / `Medium` / `High` / `Highest` / `Max` — and you decide. Some relay models force reasoning on but use non-standard names, and this still lets you adjust manually. The choice is remembered and carried over when a new session uses this model.

**Test**: every model row has a "Test" button that sends one real request to verify connectivity, with the result shown below the row.

**Reorder**: long-press and drag the model name area to reorder. Changes take effect immediately (the model picker and default model list both follow this order) and survive restart.

**Delete**: swipe a model row left to delete. If a deleted model is in use (the current provider's selected model, the new-session default, the image recognition / generation / compression / title model, or a model bound to a historical session), related selections fall back to the default model automatically — no more requests go out with the deleted model.