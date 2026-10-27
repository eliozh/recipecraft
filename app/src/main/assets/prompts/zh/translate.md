请把这份来自食谱工坊应用的食谱翻译成{{languageName}}。食谱的 JSON 如下：

<<<
{{recipe}}
>>>

请以一个 JSON 对象返回同一份食谱，键名和结构保持不变。只把下列文字字段翻译成{{languageName}}：
- `name`
- 每种食材的 `name`、`quantity` 和 `unit`（"1 1/2" 这样的数字保持原样，"a pinch" 这样的文字要翻译）
- `tools` 中的每一项
- 每个步骤的 `text`

其余内容一律保持原样：`mealType`、`servings` 和 `aiKcal`；每种食材的 `nameEn`、`grams` 和 `aiKcal`；以及每个步骤的 `minutes`。`nameEn` 始终保持英文。食材、厨具和步骤的数量和顺序都不能变：不要增加、删除、合并或拆分任何一项。译文要像菜谱一样自然地道，并保留原意。

不要写任何说明文字、代码块标记或注释，只返回 JSON 对象。
