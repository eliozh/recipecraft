Change this recipe from the RecipeCraft app as the user asks. Here it is as JSON:

<<<
{{recipe}}
>>>

What the user wants changed: <<<{{instruction}}>>>

Rules:
- Return the whole recipe with the change made, not only the parts that changed. Keep what the request does not touch as it was.
- Keep the same `mealType`, since the recipe may belong to a full-day set. Leave out `"set"`.
- Write every text field in {{languageName}}. `nameEn` always stays in English.
- Give every ingredient its `nameEn`, `grams` and `aiKcal`, and give the recipe its `aiKcal`, all worked out for the changed recipe.

Reply in the recipe format from your instructions: one short sentence, then exactly one ```json block whose "recipes" array holds exactly one recipe, the changed one.
