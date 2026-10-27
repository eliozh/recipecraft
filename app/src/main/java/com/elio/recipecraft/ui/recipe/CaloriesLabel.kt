package com.elio.recipecraft.ui.recipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elio.recipecraft.R
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories

/** The calorie total and per-serving figure, with a chip that says where the figure comes from. */
@Composable
fun CaloriesLabel(calories: Calories, modifier: Modifier = Modifier) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val database = calories.source == CalorieSource.DATABASE
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (database) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                stringResource(if (database) R.string.recipe_calories_database else R.string.recipe_calories_ai_estimate),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).testTag(CaloriesLabelTestTags.SOURCE),
            )
        }
        val total = calories.total
        Text(
            if (total == null) {
                stringResource(R.string.recipe_calories_unknown)
            } else {
                listOfNotNull(
                    pluralStringResource(R.plurals.recipe_calories_total, total, total),
                    calories.perServing?.let { pluralStringResource(R.plurals.recipe_calories_per_serving, it, it) },
                ).joinToString(" · ")
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(RecipeCardTestTags.CALORIES),
        )
    }
}

object CaloriesLabelTestTags {
    const val SOURCE = "calories_source"
}
