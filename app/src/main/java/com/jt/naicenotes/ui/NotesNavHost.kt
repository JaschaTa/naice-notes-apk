package com.jt.naicenotes.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jt.naicenotes.ui.home.HomeScreen
import com.jt.naicenotes.ui.scan.ScanRecipeScreen

private object Routes {
    const val HOME = "home"
    const val SCAN = "scan?sectionId={sectionId}"
    fun scan(sectionId: Long?): String =
        "scan?sectionId=${sectionId ?: -1L}"
}

@Composable
fun NotesNavHost(initialSectionId: Long? = null) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                initialSectionId = initialSectionId,
                onScan = { sectionId ->
                    navController.navigate(Routes.scan(sectionId))
                },
            )
        }
        composable(
            route = Routes.SCAN,
            arguments = listOf(
                navArgument("sectionId") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) { entry ->
            val raw = entry.arguments?.getLong("sectionId") ?: -1L
            val sectionId = raw.takeIf { it >= 0 }
            ScanRecipeScreen(
                initialSectionId = sectionId,
                onClose = { navController.popBackStack() },
            )
        }
    }
}
