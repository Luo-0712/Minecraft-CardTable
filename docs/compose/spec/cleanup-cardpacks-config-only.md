---
feature: cleanup-cardpacks-config-only
status: delivered
updated: 2026-09-11
branch: cleanup/cardpacks-config-only
commits: cc1ac06..e859dc2
---

# 牌包清理：仅保留 config 路径下的 UNO 与标准扑克

## Report

**What was built** — 模组 jar 不再携带任何内置牌包。标准扑克迁到版本可控的 `content-packs/standard/`（pack/layout/cards + 53 张贴图），开发时用 `scripts/sync-content-packs.ps1` 同步到 `run/config/cardtable/packs/`。`ContentPackLoader.BUILTIN_PACKS` 清空，运行时只扫描 config 文件包。删除 `demo_poker` / `demo_battle` 全部资源与 `DemoPacksFeasibilityTest`；`CardFaceStateTest` 改为 API builder 最小 layout，继续锁定翻面/手牌契约。主仓 `run/config/cardtable/packs/` 现只含 `uno_cards` 与 `standard`（已删 `demo_tcg`）。

**Verification** — `./gradlew test`：BUILD SUCCESSFUL，15 个测试类共 102 用例，0 fail。`CardFaceStateTest` 7/7；content 编解码与握手测试全部通过。Review：PASS（无 critical/major）。

**Journey log**
- 主仓 `git worktree add` 被环境拦截；改为本地 clone 到 `.worktrees/cleanup-cardpacks` 并在其中建分支，避免碰主工作区未提交 UI 改动。
- `run/` 被 gitignore：标准扑克源必须落在 `content-packs/`，否则清理后版本库无副本。
- git rename 探测把部分相同的 demo_battle 贴图显示成 standard 的来源；实际字节来自 standard 原目录，53 张齐全。
- `*.ps1` 默认 ignore，需 `!scripts/sync-content-packs.ps1` 例外才能把同步脚本入库。

## [S1] Problem

模组 jar 内仍打包开发期内置牌包（`standard` / `demo_poker` / `demo_battle`），dev 的 `run/config/cardtable/packs/` 还留着测试包 `demo_tcg`。加载器硬编码 `BUILTIN_PACKS`，可行性测试也绑死这些 demo 资源。目标运行时内容只剩 config 文件包：UNO 与标准扑克。

## [S2] Design

1. **仓库源包**：标准扑克迁到版本可控的 `content-packs/standard/`（`pack.json` + `layout.json` + `cards.json` + `textures/`）。开发时同步到 `run/config/cardtable/packs/standard/`。
2. **删除内置资源**：移除 `src/main/resources/assets/cardtable/cardpacks/` 下全部牌包，以及 `textures/card/{standard,demo_poker,demo_battle}/`。保留 `textures/card/default_back.png`（界面兜底牌背）。
3. **加载器**：`ContentPackLoader.BUILTIN_PACKS` 置空；保留 classpath 加载代码路径，便于以后可选再挂内置包。运行时只扫描 `config/cardtable/packs/`。
4. **测试**：删除 `DemoPacksFeasibilityTest`。`CardFaceStateTest` 改为用 API builder 构造最小 layout（deck + draw/flip），不再依赖类路径 demo 包。
5. **文档**：README 更新为「仅 config 文件包」；说明 `content-packs/standard` 与 run 同步方式。
6. **Dev 运行时（gitignored）**：`run/config/cardtable/packs/` 只保留 `uno_cards` 与 `standard`；删除 `demo_tcg`。

## [S3] Out of Scope

- 不删除 `assets/uno-cards/` 贴图源素材
- 不改握手协议、动态纹理命名空间、pack JSON schema
- 不实现自动 Gradle 同步任务（手动/脚本复制即可）
- 不处理主工作区未提交的 UI 改动

## Tasks

- [x] T1: 新建 `content-packs/standard` 并迁入标准扑克 JSON+贴图 — acceptance: 目录含 pack/layout/cards/textures，内容与原内置包一致 (covers: S2.1)
- [x] T2: 删除 jar 内置 cardpacks 与 demo/standard 贴图 — acceptance: resources 下无 cardpacks，无 demo_*，无 standard 贴图目录，default_back 仍在 (covers: S2.2)
- [x] T3: 清空 BUILTIN_PACKS 并更新 javadoc — acceptance: 编译通过，不再加载内置包 (covers: S2.3)
- [x] T4: 删除 DemoPacksFeasibilityTest，重写 CardFaceStateTest 去 demo 依赖 — acceptance: `./gradlew test` 相关用例通过 (covers: S2.4)
- [x] T5: 更新 README 内容包说明 — acceptance: 文档只描述 config 路径与 content-packs 源 (covers: S2.5)
- [x] T6: 同步主仓 run/config 仅留 uno_cards+standard — acceptance: packs 下无 demo_tcg，有 standard 与 uno_cards (covers: S2.6)
