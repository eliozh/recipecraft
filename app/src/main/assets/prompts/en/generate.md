Create recipes with these settings:

- Scope: {{scope}}
- Count: {{count}}
- Recipes in total: {{total}}
- Preferences (blank means none): <<<{{preferences}}>>>

How to read the settings:
- Scope `full_day_set` asks for {{count}} full-day set(s). A set is one breakfast, one lunch and one dinner that make a balanced day together. Give every recipe a `"set"` number: 1 for the recipes of the first set, 2 for the second, and so on. List the recipes set by set, each set in the order breakfast, lunch, dinner. Vary the dishes from one set to the next.
- Any other scope is a meal type. Give exactly {{count}} clearly different recipes, all with that `mealType`, and leave out `"set"`.
- The preferences apply to every recipe.

Reply in the recipe format from your instructions: one short sentence, then exactly one ```json block whose "recipes" array holds all {{total}} recipes.
