# Documentation Language Guide | 文檔語言選擇指南

This document helps you choose the right language version for SimpleEC OMS documentation.

---

## English (英文) - Original Version

**For whom**: International investors, global teams, international users

**When to use**:
- You prefer English documentation
- Working with international stakeholders
- Contributing to global team discussions
- Reading technical specifications for international audiences

**Documentation**:
- [README.md](README.md) - Project overview
- [GLOSSARY.md](GLOSSARY.md) - Complete terminology
- [CLAUDE.md](CLAUDE.md) - Developer guide
- All files in [docs/](docs/) directory

---

## 繁體中文 (Traditional Chinese) - Taiwan Version

**適用對象**: 台灣使用者、Tom（創始人）、台灣商家、繁體中文使用者

**使用時機**:
- 習慣繁體中文的用戶
- SimpleEC OMS 主要用戶
- 台灣、香港、澳門的商家

**推薦**: 如果你是 Tom 或台灣用戶，**使用這個版本**。

**文檔列表**:
- [README.zh-TW.md](README.zh-TW.md) - 專案概覽（繁體中文）
- [GLOSSARY.zh-TW.md](GLOSSARY.zh-TW.md) - 完整術語表（繁體中文）
- [LANGUAGE-GUIDE.md](LANGUAGE-GUIDE.md) - 本文件

**特色**:
- 使用台灣通用詞彙
- 貨幣單位：新台幣 (TWD) 或美元 (USD)
- 日期格式：2026-02-27

---

## 簡體中文 (Simplified Chinese) - Mainland China Version

**使用人群**: 中國大陸用戶、習慣簡體中文的用戶

**使用時機**:
- 中國大陸用戶
- 習慣簡體中文的用戶

**文檔列表** (未來實現):
- README.zh-CN.md - 專案概覽（簡體中文）
- GLOSSARY.zh-CN.md - 術語表（簡體中文）

**狀態**: 📋 計畫中（下個月實現）

---

## Quick Navigation | 快速導航

### 我是誰？→ 我應該看哪份文檔？

| 你是誰 | 推薦版本 | 主要文檔 |
|--------|---------|---------|
| **Tom（台灣創始人）** | 繁體中文 | [README.zh-TW.md](README.zh-TW.md) |
| **台灣商家** | 繁體中文 | [README.zh-TW.md](README.zh-TW.md) |
| **香港/澳門用戶** | 繁體中文 | [README.zh-TW.md](README.zh-TW.md) |
| **中國大陸用戶** | 簡體中文 | [README.zh-CN.md](README.zh-CN.md) (未來) |
| **國際投資人** | English | [README.md](README.md) |
| **國際開發者** | English | [CLAUDE.md](CLAUDE.md) |
| **技術團隊** | English | [docs/](docs/) |

---

## Document Mapping | 文檔對應表

### Priority 1 (已翻譯 - Translated)

| 文檔 | 英文 | 繁體中文 | 簡體中文 | 完成日期 |
|------|------|---------|---------|---------|
| **主頁** | [README.md](README.md) | [README.zh-TW.md](README.zh-TW.md) | [README.zh-CN.md](README.zh-CN.md) | 2026-02-27 |
| **術語表** | [GLOSSARY.md](GLOSSARY.md) | [GLOSSARY.zh-TW.md](GLOSSARY.zh-TW.md) | [GLOSSARY.zh-CN.md](GLOSSARY.zh-CN.md) | 2026-02-27 |
| **語言指南** | [LANGUAGE-GUIDE.md](LANGUAGE-GUIDE.md) | 本文件 | [LANGUAGE-GUIDE.zh-CN.md](LANGUAGE-GUIDE.zh-CN.md) | 2026-02-27 |

### Priority 2 (規劃中 - Planned)

| 文檔 | 英文 | 繁體中文 | 簡體中文 | 進度 |
|------|------|---------|---------|------|
| **CLAUDE 開發指南** | [CLAUDE.md](CLAUDE.md) | CLAUDE.zh-TW.md | CLAUDE.zh-CN.md | ⏳ 待實現 |
| **快速開始** | [docs/0-START/QUICK_START.md](docs/0-START/QUICK_START.md) | QUICK_START.zh-TW.md | QUICK_START.zh-CN.md | ⏳ 待實現 |
| **快速命令** | [docs/0-START/QUICK_COMMANDS.md](docs/0-START/QUICK_COMMANDS.md) | QUICK_COMMANDS.zh-TW.md | QUICK_COMMANDS.zh-CN.md | ⏳ 待實現 |
| **系統狀態** | [docs/6-OPERATIONS/OPERATIONS_CURRENT_STATUS.md](docs/6-OPERATIONS/OPERATIONS_CURRENT_STATUS.md) | OPERATIONS_CURRENT_STATUS.zh-TW.md | OPERATIONS_CURRENT_STATUS.zh-CN.md | ⏳ 待實現 |

---

## How to Choose Your Language | 如何選擇你的語言

### If you are a Taiwan user (台灣用戶)

**你應該閱讀**:
1. [README.zh-TW.md](README.zh-TW.md) - 了解系統概覽
2. [GLOSSARY.zh-TW.md](GLOSSARY.zh-TW.md) - 查詢術語定義
3. [LANGUAGE-GUIDE.md](LANGUAGE-GUIDE.md) - 尋找其他文檔

**快速開始**:
```bash
# 查看繁體中文主頁
cat README.zh-TW.md

# 搜尋術語
grep "API" GLOSSARY.zh-TW.md
```

### If you are an international investor (國際投資人)

**You should read**:
1. [README.md](README.md) - Project overview
2. [GLOSSARY.md](GLOSSARY.md) - Complete terminology
3. [CLAUDE.md](CLAUDE.md) - Development guide
4. [docs/](docs/) - Technical specifications

**Quick start**:
```bash
# View English README
cat README.md

# Search glossary
grep "ARR" GLOSSARY.md
```

### If you are a developer (開發者)

**English speakers**:
1. [CLAUDE.md](CLAUDE.md) - Developer workflow guide
2. [docs/1-ARCHITECTURE/](docs/1-ARCHITECTURE/) - System design
3. [docs/3-EVENT-FLOW/](docs/3-EVENT-FLOW/) - Event architecture

**Traditional Chinese speakers** (繁體中文):
1. [README.zh-TW.md](README.zh-TW.md) - System overview
2. [GLOSSARY.zh-TW.md](GLOSSARY.zh-TW.md) - Terminology reference
3. English docs for technical details (暫無中文翻譯)

---

## Documentation Structure | 文檔結構

### Root Level (根目錄)

| File | Purpose | Languages |
|------|---------|-----------|
| **README.md** | Project overview | English |
| **README.zh-TW.md** | Project overview | Traditional Chinese |
| **GLOSSARY.md** | Terminology | English |
| **GLOSSARY.zh-TW.md** | Terminology | Traditional Chinese |
| **LANGUAGE-GUIDE.md** | Choose your language | Bilingual |
| **CLAUDE.md** | Developer workflow | English |
| **WORK_PRINCIPLES.md** | Core principles | English |

### docs/ Directory (文檔目錄)

All technical documentation is in English for now. We plan to add Traditional Chinese translations in the future.

```
docs/
├── 0-START/              # Quick reference
│   ├── README.md
│   ├── QUICK_START.md
│   └── QUICK_COMMANDS.md
├── 1-ARCHITECTURE/       # System design
│   ├── ARCHITECTURE_OVERVIEW.md
│   └── DESIGN_v2.md
├── 3-EVENT-FLOW/         # Event architecture
│   ├── CORE_CONTRACTS.md
│   └── DATA_FLOW_MAPPING.md
├── 4-SCHEMA/             # Database schema
│   └── SCHEMA.md
├── 6-OPERATIONS/         # Operations guide
│   ├── DEPLOYMENT_GUIDE.md
│   └── OPERATIONS_RUNBOOK.md
└── ...                   # Other documents
```

---

## Terminology Consistency | 術語一致性

When reading documentation in **Traditional Chinese**, use the terminology from [GLOSSARY.zh-TW.md](GLOSSARY.zh-TW.md) to understand technical terms.

### Example translations:

| English | 繁體中文 |
|---------|---------|
| OMS | 訂單管理系統 |
| Order Management System | 訂單管理系統 |
| Channel | 通路 |
| Marketplace | 電商平台 |
| Merchant | 商家 |
| Kafka | Kafka (保持英文) |
| Event | 事件 |
| Handler | 處理器 |
| Consumer | 消費者 |

---

## How to Contribute Translations | 如何貢獻翻譯

### If you want to add more translations:

1. **Choose a document** (選擇文檔)
   - Priority 1: README, GLOSSARY, QUICK_COMMANDS
   - Priority 2: CLAUDE, ARCHITECTURE guides
   - Priority 3: Other technical docs

2. **Follow the naming convention** (遵循命名規則)
   - Original: `README.md`
   - Traditional Chinese: `README.zh-TW.md`
   - Simplified Chinese: `README.zh-CN.md`

3. **Use consistent terminology** (使用一致的術語)
   - Always refer to [GLOSSARY.zh-TW.md](GLOSSARY.zh-TW.md) or [GLOSSARY.zh-CN.md](GLOSSARY.zh-CN.md)
   - Keep platform names in English (Shopee, Momo, Yahoo, etc.)
   - Keep technical terms standardized

4. **Maintain structure and formatting** (保持結構和格式)
   - Same headings as original
   - Same tables and lists
   - Same internal links (but point to .zh-TW or .zh-CN versions)

5. **Update this guide** (更新本指南)
   - Add the new document to the mapping table
   - Mark completion date

---

## Translation Checklist | 翻譯檢查清單

Use [TRANSLATION-CHECKLIST.md](TRANSLATION-CHECKLIST.md) to ensure quality translations.

**Before submitting a translation**:
- [ ] All terminology is consistent with GLOSSARY
- [ ] All headings and structure match original
- [ ] All internal links point to correct versions
- [ ] No spelling errors
- [ ] Technical terms are accurate
- [ ] Formatting (tables, lists, code blocks) is preserved

---

## File Location Rules | 文件位置規則

### Root-level documents

These should be in the **project root** (`/home/tom/ONEEC/simpleec-oms/`):
- README.md / README.zh-TW.md / README.zh-CN.md
- GLOSSARY.md / GLOSSARY.zh-TW.md / GLOSSARY.zh-CN.md
- LANGUAGE-GUIDE.md / LANGUAGE-GUIDE.zh-TW.md / LANGUAGE-GUIDE.zh-CN.md
- TRANSLATION-CHECKLIST.md
- CLAUDE.md (English only for now)
- WORK_PRINCIPLES.md (English only for now)

### Technical documentation

These should be in the **docs/** directory:
- All architecture, design, and operational guides
- English first, translations added later

---

## Maintenance Schedule | 維護計畫

### Weekly (每週)
- Check for new questions in README
- Monitor documentation issues

### Monthly (每月)
- Sync all language versions
- Update terminology in GLOSSARY
- Review translation quality

### Quarterly (每季)
- Add new translations for high-priority documents
- Update status in this guide

**Last sync**: February 27, 2026
**Next sync**: March 27, 2026

---

## Questions? | 有問題？

**If you can't find what you need**:

1. **English documentation**: Use [GLOSSARY.md](GLOSSARY.md) and [docs/](docs/)
2. **Traditional Chinese documentation**: Use [GLOSSARY.zh-TW.md](GLOSSARY.zh-TW.md)
3. **Report an issue**: Check the project's GitHub Issues page
4. **Ask for help**: Contact the development team or use `/help` command in Claude

---

**Language Coordinator**: SimpleEC Team
**Last Updated**: February 27, 2026
**Supported Languages**: English, Traditional Chinese (繁體中文)
**Planned Languages**: Simplified Chinese (簡體中文) - Coming soon
