# knowledge/ 知识库导入件目录

> 建立日期：2026-10-13
> 用途：阶段 2.3 真实知识导入的唯一入库来源，按《阶段 2.3 真实知识导入清单》第 3 节的主题划分与 `sourceId` 命名建立。
> 重要：本目录下只有 `<sourceId>.md` 是知识文档。本文件不是知识文档，**不要**上传。

---

## 一、目录内容

共 20 篇：7 篇绿植 + 12 篇宠物 + 1 篇平台规则（清单第 3.3 节的可选 B1）。

| sourceId | 主题 | 文件字符数 | 入库字符数 | 分块 |
| --- | --- | ---: | ---: | ---: |
| `kb_plant_watering` | 浇水与积水：黄叶、掉叶、烂根早期与浇水频率 | 637 | 616 | 2 |
| `kb_plant_root_repot` | 烂根处理与换盆：修根、消毒、上盆 | 541 | 522 | 2 |
| `kb_plant_light_etiolation` | 光照与徒长 | 337 | 325 | 1 |
| `kb_plant_soil_iron` | 土壤酸碱与缺铁性黄叶 | 411 | 396 | 1 |
| `kb_plant_leaf_pest` | 叶片卷曲排查与蚜虫防治 | 468 | 450 | 1 |
| `kb_plant_powdery_mildew` | 白粉病：室内诱因与复发控制 | 422 | 409 | 1 |
| `kb_plant_black_spot` | 月季黑斑病 | 369 | 357 | 1 |
| `kb_pet_toxic_food` | 猫狗禁食清单与中毒风险 | 421 | 406 | 1 |
| `kb_pet_poisoning_first_aid` | 误食与中毒急救流程 | 413 | 397 | 1 |
| `kb_pet_toxic_plant` | 对宠物有害的常见植物 | 430 | 412 | 1 |
| `kb_pet_digestive_emergency` | 呕吐、腹泻、便血的就医分级 | 398 | 378 | 1 |
| `kb_pet_respiratory` | 猫呼吸道症状判断 | 371 | 353 | 1 |
| `kb_pet_vaccination` | 猫核心疫苗与接种评估 | 448 | 425 | 1 |
| `kb_pet_parasite_control` | 体内外驱虫频率与剂量 | 442 | 424 | 1 |
| `kb_pet_small_pet_abnormal` | 兔、仓鼠、鹦鹉异常识别 | 458 | 439 | 1 |
| `kb_pet_bite_wound` | 动物咬伤处理与人畜共患 | 351 | 333 | 1 |
| `kb_pet_infectious_disease` | 犬瘟与细小的区别 | 480 | 453 | 1 |
| `kb_pet_emergency_triage` | 急症分级：抽搐与车祸后处理 | 389 | 370 | 1 |
| `kb_pet_medication_boundary` | 用药与处方边界 | 476 | 450 | 1 |
| `kb_platform_care_boundary` | 平台护理边界声明 | 475 | 452 | 1 |

`文件字符数` 是工作区文件按 CRLF 计算的 `length()`，`入库字符数` 是 2026-10-13 逐篇 `dryRun` 返回的 `chars`，即 `KnowledgeImportService.normalizeText` 把 CRLF 归一为 LF 之后的 `length()`，两者差值正好等于各文件的换行符数量。合计 8737 / 8367 字符，分块总数 22，符合清单「每个 `sourceId` 1 至 3 块」的约束。

---

## 二、上传规则（最容易踩的坑）

`KnowledgeImportService.resolveSourceId` 在**不传** `sourceId` 时，会用文件名哈希生成 `kb_<32 位 hash>`。这个值永远不会等于题库里的 `expectedSourceIds`，会导致 `Recall@K` 恒为 0 且不易察觉。

**每次上传都必须显式传 `sourceId`，且取值等于文件名去掉 `.md`。**

```bash
# 先 dryRun 核对分块与 sourceId，不写库
curl -s -b "cookie.txt" -X POST "http://127.0.0.1:8080/api/kb/upload" \
  -F "file=@knowledge/kb_plant_watering.md" \
  -F "sourceId=kb_plant_watering" -F "dryRun=true"

# 分块确认后正式入库（importMode=replace，同 sourceId 会整体替换）
curl -s -b "cookie.txt" -X POST "http://127.0.0.1:8080/api/kb/upload" \
  -F "file=@knowledge/kb_plant_watering.md" \
  -F "sourceId=kb_plant_watering"

curl -s -b "cookie.txt" "http://127.0.0.1:8080/api/kb/list"
```

入库语义是**按 `sourceId` 整体替换**，重复上传不会产生副本，但会覆盖上一版，所以改稿后直接重传即可。

---

## 三、定版记录

本目录取自两个来源压缩包，其中两个 `sourceId` 在两包中同时存在且内容不同，裁决如下：

| sourceId | 定版依据 | 未采用版本 |
| --- | --- | --- |
| `kb_plant_watering` | 采用 `knowledge-all.zip` 版本（637 字，含浇水频率、多肉干透再浇、常见错误） | `kb-docs.zip` 版本（623 字，含多肉配土比例、水培转土培） |
| `kb_pet_respiratory` | 采用 `knowledge-all.zip` 版本（371 字，按精神 / 食欲 / 呼吸分级判断） | `kb-docs.zip` 版本（328 字） |

其余 18 篇仅在单一包中出现，无冲突。20 篇正文均与来源压缩包内条目 SHA-256 一致，未做改写。

---

## 四、导入顺序

1. 逐篇 `dryRun`，核对返回的 `sourceId`、`count`、`chars` 与本文件第一节表格一致；
2. 分批正式入库，每批后调 `/api/kb/list` 记录公共向量数量变化；
3. 删除测试种子 `post_1`、`post_2`，确认公共向量来源只剩真实 `sourceId`；
4. 按《阶段 2.3 真实知识导入清单》第四节更新 `TEST/eval/ai-eval-v1.jsonl` 的 `expectedSourceIds`；
5. 打开受控召回观测并把评测账号加入白名单；
6. 用同一份 40 题复测，输出 `Recall@K` 与引用命中率，保存计算口径、配置版本与原始 JSONL。

第 3 步之后的验收细节见 `落地文档/阶段2.3真实知识导入清单-2026-10-11.md` 第七节。

---

## 五、未纳入本目录的材料

| 材料 | 处置 |
| --- | --- |
| `kb-docs.zip` 中除 `kb_platform_care_boundary.md` 外的 20 篇 | 留作第二阶段日常养护扩充集。命名与清单不一致，且缺 11 个映射来源，直接入库会让 `expectedSourceIds` 全部落空 |
| `植物与宠物健康知识库_合并版.md` | 14 个主题混于一个文件（约 33 个块），不符合「一个 `sourceId` 对应一个主题」约束，只作内容底稿与评审附录，已存为 `落地文档/植物与宠物健康知识库-合并版-底稿-2026-10-13.md` |

---

## 六、来源追溯

每篇 `sourceId` 对应的公开来源见 `落地文档/阶段2.3知识库来源对照表-2026-10-13.md`。清单第六节要求「每条 `sourceId` 元数据记录实际引用了哪几条 URL」，当前上传接口只写入 `type` / `fileName` / `importMode` 三项元数据，尚无自定义字段入口，因此来源以独立对照表维护。

---

## 七、已知待办

1. `kb_pet_toxic_plant`、`kb_pet_infectious_disease`、`kb_pet_emergency_triage` 三篇在底稿中没有对应的文献依据段落，来源待补。
2. 底稿参考列表中两条 URL 在转存时已损坏，未修复前不得作为评审引用。
3. `kb_pet_bite_wound` 写「冲洗至少数分钟」，与人畜共患处置规范的「不少于 15 分钟」口径不一致，需定稿统一。
4. `kb_plant_soil_iron` 两版口径不一致：本目录采用「硫酸亚铁稀释液」，底稿指出高 pH 下普通铁肥无效、需 EDDHA 螯合铁。底稿更准确，后续改稿时以底稿为准。
5. 本目录单篇字数 337 至 637 字，低于执行方案建议的 500 至 1500 字区间下限，目前每篇 1 至 2 块仍在清单允许范围内；若要提高召回稳定性，可在不改主题划分的前提下补细节。

---

## 八、执行记录（2026-10-13）

在本地 MySQL 与本地 8080 构建（`main` 提交 `493447d`）上，使用白名单内的导入账号完成首次入库：

- `dryRun`：20 篇逐篇通过，`sourceId` 回显与本表一致，块数全部吻合（2 + 2 + 18 × 1 = 22）。
- 正式入库：20 篇全部成功，`replacedPreviousCount=0`（入库前公共库为空）；删除测试种子 `post_1`、`post_2` 后 `/api/kb/list` 为 `DOCS=20`、`VECTORS=22`，无种子残留。
- 题库映射：`TEST/eval/ai-eval-v1.jsonl` 按《阶段 2.3 真实知识导入清单》第四节更新 29 行 `expectedSourceIds`，其余 11 行（`path: tools`、`path: memory`、`refusal-001`）保持空数组。
- 复测：12 题冒烟 + 40 题正式题库，`Recall@K = 65.38%`（39 样本），明细见 `TEST/README.md` 的「阶段 2.3 真实知识导入与召回复测（2026-10-13）」。
- 范围：本次导入只作用于本地库；线上 `https://sekaipetplant.com` 仍是旧数据，需在生产环境按同样顺序重做后再验收第七节的待办项。
