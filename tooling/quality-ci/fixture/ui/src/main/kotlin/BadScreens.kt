package fixture.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavController as ImportedController
import fixture.contracts.Controller

@Composable fun DirectScreen(controller: androidx.navigation.NavController) {}
@Composable fun AliasScreen(controller: Controller) {}
@Composable fun SubtypeScreen(controller: androidx.navigation.NavHostController) {}
@Composable fun GenericScreen(controller: List<ImportedController?>) {}
