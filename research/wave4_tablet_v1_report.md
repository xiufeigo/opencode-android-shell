# Wave 4 acceptance — Tablet (emulator-5556) × v1 protocol (localhost:4096, 1.18.31)

Date: 2026-09-22. App: `com.xiufeigo.opencodeshell` v1.4.0 (`OcActivity`).
Server: v1 serve 1.18.31, no auth. Model: `opencode-go/muse-spark-1.3-contributor`.
Screenshots: `research/wave4_shots/tablet_v1_*.png` (97 files). VERIFICATION ONLY — no source touched.

## Checklist

1. **Connect (v1)** — PASS. App launched to prior `android-e2e` session; server healthy
   (`{"healthy":true,"version":"1.18.31"}`); all subsequent behavior is V1 legacy
   (question dock, share/archive rows, bare-array `GET /session`).
   Evidence: `tablet_v1_01_launch.png`, `tablet_v1_02_home.png`.
2. **New session + table prompt** — PASS. New session created via `+`; model chip
   `Muse Spark 1.3 Contributor`; prompt `reply with a short markdown table of 3
   databases and their query language then one sentence` streamed and rendered a
   real table (PostgreSQL/SQL, MongoDB/MQL, Neo4j/Cypher) + closing sentence +
   `6 秒` badge; session auto-titled `Database query languages table`.
   Evidence: `tablet_v1_03_newsession.png`, `tablet_v1_23_prompt_ok.png`,
   `tablet_v1_26_resend.png`, `tablet_v1_27_queuecheck.png`.
3. **v1-only Share/Archive** — PASS. ⋮ menu contains 分享会话 + 归档会话.
   Share → `发布到网页` dialog with live link `https://opncd.ai/share/evUCCKZs` +
   `分享链接已复制到剪贴板` toast; 取消发布 → `已取消分享会话` toast (server-confirmed
   round-trip). Archive → confirm dialog (cancelled, session untouched). No 404s.
   Evidence: `tablet_v1_34_session_menu.png`, `tablet_v1_35_share.png`,
   `tablet_v1_37_share.png`, `tablet_v1_38_unshared.png`, `tablet_v1_39_reshare.png`,
   `tablet_v1_50_menu2.png`.
4. **Queue absence on v1** — PARTIAL (PASS with gap). During running turns (■ visible)
   the composer shows only placeholder + model chip — no queue panel, no Steer/Queue
   toggle anywhere (`26`, `32`, `41`). Settings → Follow-up behavior = `Steer`
   (`75`). A dedicated mid-turn second-message keystroke was not captured (model
   turns finished in 4–9 s, faster than the automation loop).
   Evidence: `tablet_v1_26_resend.png`, `tablet_v1_32_essay_sent.png`,
   `tablet_v1_41_tool_running.png`, `tablet_v1_75_settings.png`.
5. **Question dock (v1)** — PASS, full cycle. Prompt `please ask me a question with 3
   options to choose from` → `提问` dock: `How would you like to proceed after the
   ls -la error?` + 3 radio options + `自定义…` + 拒绝/提交. Selected option 2,
   提交 → dock resolved into timeline row `🚩 Question 1 question`, turn continued.
   Evidence: `tablet_v1_45_relaunch.png`, `tablet_v1_46_qselected.png`,
   `tablet_v1_47_qanswered.png`, `tablet_v1_48_qanswered.png`.
6. **Tool cards + permission** — PASS (render) / permission dock NOT-TRIGGERED.
   `run the shell command ls -la…` → `使用了 1 Shell` row + `Output from ls -la in
   C:\Windows\System32:` + PowerShell error code block + model follow-up (`6 秒`).
   No permission dock appeared: app setting 自动接受权限=ON and server auto-allowed,
   so there was nothing to approve — dock behavior itself unobserved, not failed.
   Evidence: `tablet_v1_41_tool_running.png`, `tablet_v1_42_tool_done.png`,
   `tablet_v1_75_settings.png`.
7. **Abort** — PASS. 1500-word essay turn streaming (■ visible) → tapped ■ →
   `已中断` badge (`33 秒`), text cut mid-sentence, composer back to ↑. Clean.
   Evidence: `tablet_v1_32_essay_sent.png`, `tablet_v1_33_aborted.png`.
8. **Rename/delete** — PASS. ⋮ → 重命名 uses inline title edit (blur-to-save);
   header showed `wave4rename`, server title verified via API. ⋮ → 删除 →
   confirm dialog → `GET /session` 17→16 rows, `wave4rename` gone; app fell back
   to Home with updated grouped list (tab for it closed).
   Evidence: `tablet_v1_57_rename3.png`, `tablet_v1_60_renamedone.png`,
   `tablet_v1_63_closed.png`, `tablet_v1_64_delete.png`,
   `tablet_v1_72_deleted_api.png` (+ API transcript below).
9. **Shell areas** — PASS. Terminal
...[truncated 4471 chars]