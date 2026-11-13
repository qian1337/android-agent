# OpenRouter vision and tools: Debug APK

## Download on an Android phone

1. Sign in to GitHub and open the successful **Debug APK** Actions run for the repair branch.
2. Scroll to **Artifacts**, download `android-agent-debug-<run>-<attempt>` and unzip it.
3. Open `android-agent-openrouter-debug.apk`. No APK preview is needed. If the mobile page hides Artifacts, enable **Desktop site** in your browser.
4. Grant installation permission to the browser/file manager when Android requests it. Enable **Android Agent Debug** in Android accessibility settings before using device tools.

The artifact expires after 14 days. A later successful branch push can generate a new one. The workflow also supports `workflow_dispatch` once GitHub recognizes it; manual workflows normally need to exist on the default branch. No main merge is required for the branch-push build.

## Package and signing compatibility

- Debug `applicationId`: `com.foxislam.androidagent.debug`; app label: **Android Agent Debug**.
- Release `applicationId` remains `com.foxislam.androidagent`. No Release is built or published by this workflow.
- The Debug app installs beside the original app. Its app data and accessibility permission are separate; it does not inherit the original API key or chat history.
- Fresh GitHub runners generate development signing keys. Different runs can have different certificate fingerprints and may not upgrade an earlier Debug install. Android requires both the same package and a compatible signing certificate for an in-place update. Do not assume the CI APK can update a locally built Debug APK.
- Check the artifact's `signing-certificate.txt` for the public certificate SHA-256, `apk-info.txt` for package/version, `SHA256SUMS` for the binary checksum and `build-info.txt` for the source commit/run.
- No private signing key is committed, cached by this workflow or uploaded. A persistent CI signing key is intentionally not configured. No APK is automatically installed.

## Configure paid OpenRouter vision and tools

Set the endpoint to `https://openrouter.ai/api/v1/chat/completions`. Choose an available OpenRouter model whose model page advertises **image input and tool calling**, and enter its exact API model ID (not its display name). Configure your API key only inside the app. Leave Cheap model blank for the initial test; otherwise it also needs to support the requests routed to it. **Provider default** omits the reasoning override.

The protocol regression suite covers `openai/gpt-4o-mini` and `google/gemini-2.5-flash` IDs, screenshots, tool calls/results and Chinese content. These are local HTTP fixtures, not availability checks or real paid API calls. Select a currently available model on OpenRouter; the test suite does not prove remote service behavior or your account balance.

To validate on your phone, ask to open a harmless app, inspect its UI tree, and describe a screenshot. Check that the tool results and image reach the next model turn. If a request fails, preserve the complete error and request ID while removing credentials and personal screen content before sharing it.

## Code changes and evidence

The backend preserves tool IDs/names/arguments when a historical assistant turn lacks provider echo. The original code lost `tool_calls` but still sent corresponding tool results; a regression failed against the original and passed with this fix. Main/cheap/override model resolution now trims surrounding whitespace, rejects empty IDs or embedded whitespace/control characters before transport, and reports the actual sent model plus available request ID on HTTP rejection. Structured provider reasoning and screenshot user messages remain supported. Free model IDs (`openrouter/free`, `:free`) remain valid; no paid fallback is introduced.

The exact previously reported error containing `\n这是 Op is not a valid model ID` has not been reproduced against real OpenRouter. A baseline HTTP test showed the original configured `openrouter/free` ID stayed unchanged after tools, so the precise production incident is not claimed resolved. No real API credentials are used by CI.

The workflow runs all JVM unit tests, assembles Debug, checks the package ID, signature and ZIP alignment, and uploads only the APK/public metadata plus a separate test-report artifact. It has `contents: read`, uses no repository secrets, and does not merge PRs or publish Releases.
