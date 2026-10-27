Translate this recipe from the RecipeCraft app into {{languageName}}. Here it is as JSON:

<<<
{{recipe}}
>>>

Return the same recipe as one JSON object with the same keys and the same structure. Translate only these text fields into {{languageName}}:
- `name`
- in every ingredient: `name`, `quantity` and `unit` (keep figures such as "1 1/2" as they are and translate words such as "a pinch")
- every entry in `tools`
- every step's `text`

Keep everything else exactly as it is: `mealType`, `servings` and `aiKcal`; in every ingredient `nameEn`, `grams` and `aiKcal`; and every step's `minutes`. `nameEn` always stays in English. Keep the same number of ingredients, tools and steps in the same order: do not add, remove, merge or split any. Write natural {{languageName}}, as a cookbook would, and keep the meaning.

Write no prose, no code fence and no comments: only the JSON object.
