/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.declarations

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fir.FirSession

abstract class FirResolvedDeclarationStatus(
    session: FirSession,
    psi: PsiElement?
) : FirDeclarationStatus(session, psi) {
    abstract override val visibility: Visibility

    abstract override val modality: Modality
}