# Quick Start

From installation to your first AI conversation.

## System Requirements

- Android 8.0 (API 26) or higher
- arm64-v8a or x86_64 device

## Download & Install

Download from [GitHub Releases](https://github.com/jieapi/aicode/releases/latest). Pick the package that fits your device:

| Package | Best for |
| --- | --- |
| `armsolo` | Real devices (arm64), smallest size |
| `x86solo` | Emulators and Chromebooks (x86_64) |
| `universal` | Both architectures in one package, larger size |

::: warning Pick the right architecture
A mismatched architecture will break the built-in container. If you are not sure, use `universal`.
:::

Tip: you can also tap "Download APK" on the [homepage](/en/), which fetches the latest stable `universal` package through an accelerated mirror (falls back to the GitHub link automatically if the mirror is unavailable).

## 1. First Launch & Onboarding Guide

Open AiCode and you will land on the chat home with a fresh empty conversation. Grant storage permission on first use — otherwise the AI cannot access files on your phone.

On first launch, the app automatically starts an interactive spotlight onboarding guide, walking you through the complete flow: "Open sidebar → Enter Settings to configure provider and fetch models → Return home to select model → Send first message". In each step, you can tap the highlighted target or the "Next" button on the card to proceed, or tap "Skip" at any time. If you want to review the walkthrough later, you can restart it anytime via "Settings → Re-run onboarding".

## 2. Configure an AI Provider

AiCode does not provide models itself; you need to connect a model service first.

1. Tap the menu button at the top-left of the home page to open the sidebar, then tap "Settings" at the bottom.
2. Go to "AI Config → AI Providers" and tap the + at the top-right to create one.
3. Fill in four fields:
   - **Name**: an alias, e.g. "OpenAI Official".
   - **Type**: pick one of `OpenAI` / `Anthropic` / `Gemini`. Third-party relay services usually use `OpenAI`.
   - **API Key**: your key, like `sk-xxx`.
   - **Base URL**: the API root. The official URL is pre-filled once you pick a type; when using a third-party relay, clear the default and enter the relay's address. Note: **do not include `/v1` or anything after it**.

The entry saves automatically — no manual save button.

## 3. Add Models

1. Tap the provider you just created to open its edit page, then switch to the "Models" tab at the bottom.
2. Two ways to add models:
   - Tap "Fetch Models" to sync the available list from the server and check the ones you want.
   - Tap + at the top-right to type a model name manually (e.g. `gpt-4o`). Use this when a relay doesn't return the full model list.
3. Optional: tap a model row to edit its metadata (context window, pricing, and capability toggles like image input and tool calls).

Models save automatically after being added.

## 4. Select a Model

Back on the chat home, tap the model button in the toolbar above the input box and pick the model you just added. The search box at the top filters models, and models are grouped by provider.

## 5. Prepare the Execution Environment

Go to "Settings → Runtime → Container & Images". Two choices:

- **Local container** (default): uses the built-in Alpine Linux, nothing to configure.
- **Remote SSH**: use a remote server as the execution backend (see Remote SSH mode in the full documentation).

::: tip Open the terminal once first
The first-time container preparation (extracting the system, installing base tools) only triggers when you enter the **terminal page**. So go to the terminal from the title bar once, complete the initialization menu when prompted, and only then can the AI execute commands reliably.
:::

## 6. Start a Conversation

Type in the input box and hit send. The mode button on the left of the input box switches between BUILD, PLAN and AUTO — see the full documentation on the three modes for the differences.

## Useful Tips

- The model selected in an empty conversation on the home page becomes the default for new conversations.
- Image recognition, image generation and title summarization can each have a dedicated model (see Default & Dedicated Models in the full documentation).
- The AI records a snapshot before modifying code, so you can roll back safely with one tap (see Checkpoints & Undo in the full documentation).
- Before long-running tasks like compilation, enable background keep-alive in the system permissions to prevent the system from killing the process when switching away.