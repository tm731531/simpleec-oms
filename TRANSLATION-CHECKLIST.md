# Translation Checklist | 翻譯檢查清單

This document provides a quality assurance checklist for SimpleEC OMS documentation translations.

---

## Translation Standards | 翻譯標準

### Terminology Consistency (術語一致性)

#### Required: All translations must use approved terminology

**Traditional Chinese (繁體中文)** - See [GLOSSARY.zh-TW.md](GLOSSARY.zh-TW.md)

| English | 繁體中文 | 例句 |
|---------|---------|------|
| OMS | 訂單管理系統 | SimpleEC OMS 是多通路訂單管理系統 |
| Order Management System | 訂單管理系統 | 本系統是一個訂單管理系統 |
| Channel | 通路 | 我們支持 7 個通路 |
| Marketplace | 電商平台 | 蝦皮是一個電商平台 |
| Merchant | 商家 | 商家可以管理訂單 |
| Order | 訂單 | 訂單包含多個項目 |
| Item | 項目 | 每個項目代表一個商品 |
| Return | 退貨 | 顧客可以申請退貨 |
| Shipment | 出貨 | 我們會安排出貨 |
| Fulfillment | 訂單履行 | 訂單履行包括配送 |
| Inventory | 庫存 | 庫存管理是關鍵功能 |
| Pack | 套包 | 套包是通路上的商品配置 |
| SLA | 服務水準協議 | 我們有 24 小時的 SLA |
| API | API | 使用 REST API 進行集成 |
| Kafka | Kafka | Kafka 是消息代理 |
| Event | 事件 | 事件驅動架構 |
| Handler | 處理器 | 事件處理器 |
| Consumer | 消費者 | Kafka 消費者 |
| TaskType | 任務類型 | FETCH_ORDERS 是一個任務類型 |

**Keep in English (保持英文)**:
- Platform names: Shopee, Momo, Yahoo, PChome, Cyberbiz, Shopline, Shopify
- Technical terms: HTTP, JSON, YAML, SQL, NoSQL, ORM, JWT, etc.
- Service names: PostgreSQL, Redis, Nginx, Docker, etc.
- Database terms: schema, table, column, etc.

---

## Pre-Translation Checklist | 翻譯前檢查清單

Before starting a translation, verify:

- [ ] **Document selection**: Choose from Priority 1 or Priority 2 list
- [ ] **Glossary exists**: [GLOSSARY.zh-TW.md](GLOSSARY.zh-TW.md) or [GLOSSARY.zh-CN.md](GLOSSARY.zh-CN.md) is available
- [ ] **File naming**: Know the output filename (e.g., README.zh-TW.md)
- [ ] **Original format**: Understand the structure of the original document
- [ ] **Target audience**: Confirm who will read this translation

---

## Translation Process | 翻譯流程

### Step 1: Structure Check (結構檢查)

- [ ] **Headings**: All heading levels match original (# ## ### etc.)
- [ ] **Lists**: Bullet lists and numbered lists preserved
- [ ] **Tables**: All table structure intact
- [ ] **Code blocks**: All code blocks marked with ```
- [ ] **Links**: All internal links preserved (update .md filename for TW/CN versions)
- [ ] **Images**: All image references intact

### Step 2: Terminology Check (術語檢查)

- [ ] **GLOSSARY reference**: All key terms checked against GLOSSARY
- [ ] **Consistency**: Same term used throughout (no variations)
- [ ] **Technical accuracy**: Technical terms correctly translated
- [ ] **Platform names**: Platform names kept in English (Shopee, Momo, etc.)
- [ ] **Version consistency**: Matches the approved GLOSSARY version

### Step 3: Language Quality (語言品質)

#### Traditional Chinese (繁體中文)

- [ ] **Spelling**: No typos or incorrect characters
- [ ] **Grammar**: Sentences are grammatically correct
- [ ] **Punctuation**: Proper use of Traditional Chinese punctuation
- [ ] **Cultural fit**: Content appropriate for Taiwan audience
- [ ] **Readability**: Clear and easy to understand
- [ ] **Tone**: Matches the original tone (technical, instructional, etc.)

#### Simplified Chinese (簡體中文)

- [ ] **Spelling**: No typos or incorrect characters
- [ ] **Simplified characters**: All characters in Simplified Chinese
- [ ] **Grammar**: Sentences are grammatically correct (mainland China style)
- [ ] **Cultural fit**: Content appropriate for China audience
- [ ] **Readability**: Clear and easy to understand
- [ ] **Tone**: Matches the original tone

### Step 4: Format & Links (格式與鏈接)

- [ ] **Markdown format**: Valid markdown syntax throughout
- [ ] **Internal links**: Updated to point to translated versions
  - Original: `[GLOSSARY.md](GLOSSARY.md)`
  - TW: `[GLOSSARY.zh-TW.md](GLOSSARY.zh-TW.md)`
- [ ] **External links**: Unchanged (point to external sites)
- [ ] **Code blocks**: Syntax highlighting preserved
- [ ] **Special formatting**: Bold, italic, etc. preserved

### Step 5: Content Accuracy (內容準確性)

- [ ] **No content omitted**: All sentences from original included
- [ ] **No content added**: No extra content not in original
- [ ] **Accuracy**: Technical information remains accurate in translation
- [ ] **Names**: Proper names spelled correctly
- [ ] **Numbers**: All numbers, versions, and statistics correct

---

## Document-Specific Checklists | 文檔特定檢查清單

### README.md → README.zh-TW.md

- [ ] **Title and subtitle**: Both translated
- [ ] **Status section**: Status message translated
- [ ] **Quick start section**: All commands unchanged, descriptions translated
- [ ] **Architecture diagrams**: Box labels translated (ASCII art text)
- [ ] **Service endpoints table**: Descriptions translated, URLs unchanged
- [ ] **Troubleshooting section**: All solutions translated
- [ ] **Document links**: Updated to point to .zh-TW versions
- [ ] **Last updated date**: Changed to current date

### GLOSSARY.md → GLOSSARY.zh-TW.md

- [ ] **All terminology translated**: Every term has Chinese equivalent
- [ ] **Definitions accurate**: Definitions match original meaning
- [ ] **Examples provided**: Examples in Chinese context
- [ ] **Categories maintained**: Same section structure
- [ ] **Cross-references updated**: Links to other translated docs
- [ ] **Abbreviations section**: Both English and Chinese abbreviations listed

### CLAUDE.md → CLAUDE.zh-TW.md

- [ ] **Code comments**: Code is unchanged, comments translated if separate
- [ ] **Command explanations**: Commands unchanged, descriptions translated
- [ ] **Tables**: Content translated, structure preserved
- [ ] **Technical sections**: Technical accuracy maintained
- [ ] **Git commands**: Commands unchanged, explanations translated
- [ ] **Version numbers**: All versions unchanged

---

## Quality Assurance | 品質保證

### Spelling & Grammar Check (拼寫與文法檢查)

**Traditional Chinese (繁體中文)**:
- [ ] Run through grammar checker (e.g., LanguageTool)
- [ ] Check for common Taiwan-specific mistakes
- [ ] Verify punctuation marks (、。，；：？！)
- [ ] Check for consistency in spacing

**Simplified Chinese (簡體中文)**:
- [ ] Run through grammar checker
- [ ] Check for common mainland China-specific mistakes
- [ ] Verify punctuation marks follow PRC standards
- [ ] Check character conversions (Traditional → Simplified)

### Readability Check (可讀性檢查)

- [ ] **Sentence length**: Not too long (< 30 characters average)
- [ ] **Technical clarity**: Complex concepts explained clearly
- [ ] **Flow**: Smooth transitions between sections
- [ ] **Paragraph breaks**: Appropriate use of whitespace
- [ ] **Formatting**: Proper emphasis on important terms

### Translation Validation (翻譯驗證)

- [ ] **Bi-directional**: Original concept → Chinese → back to original
- [ ] **Context check**: Meaning makes sense in context
- [ ] **Equivalence**: Captures same meaning as original
- [ ] **No loss of information**: All details preserved

---

## Translation Pair Review | 翻譯對審流程

When two translators work together:

### Translator A (翻譯人 A)
- [ ] Initial translation completed
- [ ] Self-review checklist passed

### Translator B (翻譯人 B)
- [ ] Read original document
- [ ] Read translated version
- [ ] Check terminology against GLOSSARY
- [ ] Flag any inconsistencies
- [ ] Provide suggestions for improvements

### Final Review (最終審查)
- [ ] Address all feedback
- [ ] Update checklist
- [ ] Ready for submission

---

## Common Translation Mistakes to Avoid | 常見翻譯錯誤

### ❌ Mistakes (錯誤)

| Mistake | Why it's wrong | Correct way |
|---------|---|---|
| Inconsistent terminology | Creates confusion for readers | Use approved GLOSSARY |
| Over-translating technical terms | Loses technical accuracy | Keep technical terms in English |
| Literal translation | Sounds unnatural in Chinese | Translate meaning, not words |
| Mixing Traditional and Simplified | Confuses readers | Use one consistently |
| Changing structure | Reader gets lost | Preserve original structure |
| Adding personal notes | Document becomes cluttered | Keep translation pure |
| Ignoring context | Meaning becomes wrong | Understand full context first |
| Machine translation | Quality is poor | Always manual translation |

### ✅ Best Practices (最佳實踐)

- [ ] Read entire document before translating
- [ ] Translate in sections, preserving structure
- [ ] Reference GLOSSARY for every key term
- [ ] Test all links after translation
- [ ] Have a second person review
- [ ] Use consistent terminology throughout
- [ ] Preserve all technical information
- [ ] Keep the original tone and style

---

## File Review Checklist | 文件審查檢查清單

Before committing a translation:

### Pre-submission (提交前)

- [ ] File is in correct location
  - Root-level: `/home/tom/ONEEC/simpleec-oms/README.zh-TW.md`
  - Docs: `/home/tom/ONEEC/simpleec-oms/docs/...zh-TW.md`
- [ ] Filename follows convention: `FILENAME.zh-TW.md` (or `.zh-CN.md`)
- [ ] File encoding is UTF-8
- [ ] File has no trailing whitespace
- [ ] Final newline at end of file exists

### Content Verification (內容驗證)

- [ ] Markdown preview looks correct
- [ ] All links are clickable
- [ ] All tables render properly
- [ ] All code blocks display correctly
- [ ] No broken references

### Metadata (後設資料)

- [ ] Header comment added: `<!-- Traditional Chinese translation, updated YYYY-MM-DD -->`
- [ ] Original language version linked at top
- [ ] Last updated date matches current date
- [ ] Language selector added if applicable

### Git Commit (Git 提交)

- [ ] Commit message in English with structure:
  ```
  docs: Add Traditional Chinese translation for README

  - Translated README.md to README.zh-TW.md
  - Used approved terminology from GLOSSARY.zh-TW.md
  - All links updated to point to .zh-TW versions
  - Reviewed and ready for publication
  ```

---

## Completed Translations | 已完成的翻譯

| Document | English | 繁體中文 | 簡體中文 | Date | Status |
|----------|---------|---------|---------|------|--------|
| README | [README.md](README.md) | [README.zh-TW.md](README.zh-TW.md) | - | 2026-02-27 | ✅ Complete |
| GLOSSARY | [GLOSSARY.md](GLOSSARY.md) | [GLOSSARY.zh-TW.md](GLOSSARY.zh-TW.md) | - | 2026-02-27 | ✅ Complete |
| LANGUAGE-GUIDE | [LANGUAGE-GUIDE.md](LANGUAGE-GUIDE.md) | 本文件 | - | 2026-02-27 | ✅ Complete |
| TRANSLATION-CHECKLIST | [TRANSLATION-CHECKLIST.md](TRANSLATION-CHECKLIST.md) | - | - | 2026-02-27 | ✅ English Only |

---

## Next Translations | 下次翻譯 (Priority)

### Priority 1 (本月)
- [ ] CLAUDE.md → CLAUDE.zh-TW.md
- [ ] QUICK_START.md → QUICK_START.zh-TW.md
- [ ] QUICK_COMMANDS.md → QUICK_COMMANDS.zh-TW.md

### Priority 2 (下月)
- [ ] OPERATIONS_CURRENT_STATUS.md → OPERATIONS_CURRENT_STATUS.zh-TW.md
- [ ] ARCHITECTURE_OVERVIEW.md → ARCHITECTURE_OVERVIEW.zh-TW.md
- [ ] CORE_CONTRACTS.md → CORE_CONTRACTS.zh-TW.md

### Priority 3 (下季)
- [ ] All remaining docs in docs/ directory
- [ ] Simplified Chinese translations for Priority 1

---

## Translation Tools & Resources | 翻譯工具與資源

### Spell Checking (拼寫檢查)
- [LanguageTool](https://languagetool.org/) - Free, open-source
- [Grammarly](https://app.grammarly.com/) - Paid, but supports Chinese
- Built-in editor tools (VS Code Chinese extensions)

### Terminology Reference (術語參考)
- [GLOSSARY.md](GLOSSARY.md) - Official English terminology
- [GLOSSARY.zh-TW.md](GLOSSARY.zh-TW.md) - Official Traditional Chinese terminology
- [GLOSSARY.zh-CN.md](GLOSSARY.zh-CN.md) - Planned for Simplified Chinese

### Translation Memory (翻譯記憶)
- Previous translations (see "Completed Translations" section)
- Keep consistent with past decisions
- Document new terminology decisions for future reference

### Character Conversion (字體轉換)
- [OpenCC](https://github.com/BYVoid/OpenCC) - Traditional ↔ Simplified conversion
- Manual review always required (automatic conversion can introduce errors)

---

## Review Workflow | 審查流程

### For Tom (Creator)
1. **Initial review**: Check if translation quality is acceptable
2. **Terminology**: Verify use of standard terminology
3. **Approval**: Approve for publication

### For Translators (翻譯人員)
1. **Self-review**: Complete all checklists
2. **Peer review**: Request second review (if available)
3. **Submission**: Submit via Git pull request
4. **Incorporation**: Address feedback and update

---

## Maintenance & Updates | 維護與更新

### When original document changes (原文檔更新時)

1. **Identify changes**: What was added/removed/modified?
2. **Translate changes**: Update corresponding translated versions
3. **Consistency check**: Ensure terminology still consistent
4. **Update date**: Change "Last Updated" date
5. **Version sync**: Mark as aligned with original

### Monthly sync (每月同步)

- [ ] Check all translated documents
- [ ] Compare with English versions
- [ ] Update any stale content
- [ ] Verify terminology consistency
- [ ] Document any findings

---

## Questions & Issues | 問題與反饋

**If you find translation issues**:

1. Check [GLOSSARY.zh-TW.md](GLOSSARY.zh-TW.md) for terminology
2. Verify against [LANGUAGE-GUIDE.md](LANGUAGE-GUIDE.md)
3. Report in GitHub Issues with:
   - Document name
   - Specific phrase or section
   - Suggested fix

---

**Last Updated**: February 27, 2026
**Version**: 1.0
**Maintainer**: SimpleEC Team
**Review Cycle**: Monthly (every 27th)
