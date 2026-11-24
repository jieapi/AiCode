# AI Vendors & Models

AiCode does not provide models itself — you need to connect at least one model service before you can start chatting. This page covers how to set it up.

The entry point is "Settings → AI Vendors".

## Adding a Vendor

Tap + at the top-right, and a bottom sheet slides up with two tabs: "Manual Setup" and "Ready to Use".

- **Manual Setup** tab: lists mainstream model services (OpenAI, Anthropic, DeepSeek, Zhipu, Qwen, etc.) in a unified list, with **Custom Vendor** as the first entry (configure from scratch). Picking a built-in vendor pre-fills the name, upstream format and Base URL — just add your API Key in the edit page and it works. You can also modify any field.
- **Ready to Use** tab: free model services that need no registration are a placeholder for now and will be opened in a future release.

For custom setup, three fields are enough: **Upstream Format**, **API Key** and **Base URL**.

- **Upstream Format**: pick one of `OpenAI` / `Anthropic` / `Gemini` — it decides which protocol is used for requests. For third-party relay services, `OpenAI` is almost always the right choice.
- **API Key**: your key, like `sk-xxx`. Extra spaces or newlines around it are cleaned automatically on save.
- **Base URL**: only the API root address, **do not include `/v1` or any path after it**. Leave it empty to use the official address for the upstream format.
- **API path**: the request path right after the Base URL, defaults to `/chat/completions` — usually you don't need to change it.
- **Name**: a recognizable name for this config, e.g. "My relay". The list auto-matches a brand icon by name (enter DeepSeek and you get DeepSeek's icon).

After setup, switch to the "Models" tab to fetch models, then you can head back to the home page and start chatting.

## Managing the Vendor List

Each row shows the name, upstream format, model count and enabled status.

- Tap a row to open its edit page.
- **Swipe a row left** to reveal a red delete button.
- **Long-press and drag the name or upstream format area** to reorder; the order survives restart.

### Batch operations

The **Select** button at the top right (the check-square icon) lets you handle several vendors at once:

1. Tap **Select** to enter multi-select mode: a checkbox appears on the left of each row and tapping anywhere on the row toggles it.
2. An action bar appears on top with "N selected", **Enable / Disable / Delete**, and an **×** on the right to leave multi-select mode (the system back gesture does the same).
3. **Select All** picks every vendor in the list; tap again to deselect all.
4. **Delete** asks for confirmation first, then removes all selected vendors and leaves multi-select mode.

While in multi-select mode, drag-to-reorder is disabled and tapping a row only toggles selection — leave the mode to edit a vendor.

## Multi-Key Mode

::: tip Version note
Multi-key mode is available since 1.11.0.
:::

If you hold multiple keys for the same service, turn on **Multi-Key Mode** in the "Options" of the edit page. When rate-limited or out of quota, the next key is tried automatically. The single-key input hides once enabled, and keys are managed on the "Multi-Key Management" page (the key you had entered automatically becomes the first entry, nothing is lost).

The management page has three sections:

**Key list**: each key is a collapsible card — collapsed it shows the index and a masked preview, tap to expand. Expanded you can edit that key and set its **weight**. Swipe a card left to delete it; long-press the card header and drag to reorder (the order survives restart). The eye icon toggles plaintext/masked display per key.

Each key can also carry a **custom Base URL**. When set, requests made with that key go to that address only; leave it blank to use the provider's Base URL. This is useful when different keys live behind different relays or self-hosted proxies. Switching keys switches the address along with it, and image generation, model discovery, connectivity tests and the dashboard follow the current key's address.

**Scheduling weight**: each key carries its own weight, default `1`, max `1,000,000`; `≤ 0` keeps the key but excludes it from scheduling. Weight only affects the **round robin** strategy — a higher value means new sessions land on that key more often (smooth proportional distribution, e.g. `1:3` is roughly a one-to-three split).

**Pickup strategy**:

- `Sequential`: always use the first key, and only switch to the next when it becomes unavailable.
- `Round robin`: new sessions start from keys in turn, weighted by each key's weight, to spread quota consumption across keys.

To protect server-side prompt caching and keep a session consistent, **one key is kept within a single session** — no frequent switching that would invalidate the server cache.

**Failover**:

- `Switch threshold`: how many consecutive failures of the same key trigger a switch, default 2.
- `Cooldown`: how long a switched-away key waits before re-joining the candidate pool, default 5 minutes; can also be disabled.

Only errors that indicate **the key itself is the problem** count as failures — auth failure, insufficient permission, rate limiting, or exhausted balance/quota. Server 5xx, timeouts and network issues can't be fixed by switching keys, so they don't consume the failure count or wrongly put a healthy key into cooldown. When a switch actually happens, the error message ends with "Switched to key N/M, retry".

After an app restart, key selection returns to the first key and cooldown records are cleared.

## New Protocol Endpoints

The "Options" in the edit page lets you switch to a vendor's newer protocol:

- **Upstream format OpenAI**: optionally enable the "Responses API";
- **Upstream format Gemini**: optionally enable the "Interactions API".

Keep them off by default. Enable only when you've confirmed the official direct service or relay you're using supports the corresponding new protocol.

When OpenAI's Responses API is enabled, an additional **Responses WebSocket** option is available. It reuses one connection for a single Agent run and sends only new input on follow-up tool turns, which is intended for long tool-heavy runs. It is off by default and requires an upstream that supports Responses WebSocket. If continuation state is lost, AiCode rebuilds the request from local conversation history. An unexpected disconnect after a request is sent is reported without automatically replaying that turn.

## Other Options

- **Maximum concurrent requests**: defaults to `0` (unlimited). Set a positive integer to queue requests after the vendor reaches that many in-flight AI requests. The chat shows a queue notice with an approximate number ahead; it returns to “Thinking” when a slot opens. Requests release their slot when completed or cancelled. Each vendor configuration has its own limit, shared by chats, vision, image generation, model fetching, and connection tests.

::: v-pre
- **Custom request headers**: append custom headers to all requests of this vendor, fully overriding same-named defaults (e.g. setting `User-Agent` replaces the default UA). Values support `{{SESSION_ID}}` (current session id) and `{{API_KEY}}` (the key actually used this time) placeholders, substituted before sending. Only needed when a relay gateway validates specific headers.
- **Custom dashboard script**: show a balance or usage card above the input box for this vendor. Scripts live in `~/.aicode/scripts/`, support Python, Bash and Node, and can be tested with "Run Test" in the edit page.
- **Script arguments**: inject extra environment variable `AICODE_KEY_<KEY>` into the dashboard script. Values support placeholders like `{{PROVIDER_API_KEY}}` (current key), `{{BASE_URL}}`, `{{MODEL}}`, substituted before the script runs.
:::

## Model Management

Switch to the "Models" tab of the edit page.

**Fetch models**: tap "Fetch Models" to sync the available list from the server. Results are grouped by brand, collapsible and searchable. Each model shows capability tags: `Image` for image support, `Tools` for tool calls, `↑` followed by context length, `↓` followed by max output.

**Manual add**: relay model lists are sometimes incomplete — tap + and type the model name. In the edit dialog you can also customize:

- **Context window**: affects when automatic context compression kicks in.
- **Output window**: the max length of a single reply.
- **Pricing**: input / output / cache-read / cache-write tiers, in USD per million tokens, used for cost statistics.
- **Capability toggles**: image input, image output, tool calls, reasoning.
- **Reasoning levels**: tick the levels this model really accepts and whether thinking can be turned off. Leave empty to use the built-in capability table; filled values win (useful when a relay renamed the model).

Your values take priority; unfilled ones fall back to auto-detected results. If auto-detection can't match the model name, it retries after stripping common suffixes like `-thinking`, `-preview`, `-high` — so most renamed relay models are still recognized.

**Thinking effort**: the lightning icon above the input box controls reasoning depth, and the label right of it always shows the level in effect (e.g. **High**; it reads **Off** when thinking is turned off, or the lowest level actually sent when the model can't be turned off). Tapping it opens a sheet with a **Thinking** switch at the top (only shown for models that can actually turn thinking off) and, below it, only the levels the model really supports — the provider default is tagged **Default**.

For OpenAI providers, the same sheet also has a manual **Fast** switch. It is independent of reasoning effort, off by default, and saved per session. Enabled requests use the Fast service tier with either Chat Completions or Responses; this may incur additional charges. OpenAI-compatible relays may ignore or reject the parameter.

- Pick a level above what the model supports and it clamps to the nearest lower level; the sheet states the outcome, e.g. **Effective: High**.
- For models that always think, the sheet says so and marks the level that will actually be sent.
- When the levels can't be detected (relay renamed the model, or a custom model), every level is listed with a "may not take effect" hint.

The choice is remembered and carried over when a new session uses this model. For the per-family support matrix and how to verify a setting really took effect, see [Reasoning modes](/advanced/reasoning-modes).

**Test**: every model row has a "Test" button that sends one real request to verify connectivity, with the result shown below the row.

**Reorder**: long-press and drag the model name area to reorder. Changes take effect immediately (the model picker and default model list both follow this order) and survive restart.

**Delete**: swipe a model row left to delete. If a deleted model is in use (the current vendor's selected model, the new-session default, the image recognition / generation / compression / title model, or a model bound to a historical session), related selections fall back to the default model automatically — no more requests go out with the deleted model.