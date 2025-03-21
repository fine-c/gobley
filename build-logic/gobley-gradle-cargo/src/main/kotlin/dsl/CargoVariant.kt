/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package gobley.gradle.cargo.dsl

import gobley.gradle.Variant
import org.jetbrains.kotlin.gradle.plugin.HasProject

/**
 * Super-interface of all variant-aware DSL objects.
 */
interface CargoVariant : HasProject, HasProfile, HasFeatures {
    /**
     * The current variant.
     */
    val variant: Variant
}
