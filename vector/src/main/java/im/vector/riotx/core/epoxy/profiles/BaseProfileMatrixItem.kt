/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.riotx.core.epoxy.profiles

import android.view.View
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import im.vector.matrix.android.api.crypto.RoomEncryptionTrustLevel
import im.vector.matrix.android.api.util.MatrixItem
import im.vector.riotx.core.epoxy.VectorEpoxyModel
import im.vector.riotx.core.extensions.setTextOrHide
import im.vector.riotx.features.crypto.util.toImageRes
import im.vector.riotx.features.home.AvatarRenderer

abstract class BaseProfileMatrixItem<T : ProfileMatrixItem.Holder> : VectorEpoxyModel<T>() {
    @EpoxyAttribute lateinit var avatarRenderer: AvatarRenderer
    @EpoxyAttribute lateinit var matrixItem: MatrixItem
    @EpoxyAttribute var editable: Boolean = true
    @EpoxyAttribute
    var userEncryptionTrustLevel: RoomEncryptionTrustLevel? = null
    @EpoxyAttribute var clickListener: View.OnClickListener? = null

    override fun bind(holder: T) {
        super.bind(holder)
        val bestName = matrixItem.getBestName()
        val matrixId = matrixItem.id
                .takeIf { it != bestName }
                // Special case for ThreePid fake matrix item
                .takeIf { it != "@" }
        holder.view.setOnClickListener(clickListener?.takeIf { editable })
        holder.titleView.text = bestName
        holder.subtitleView.setTextOrHide(matrixId)
        holder.editableView.isVisible = editable
        avatarRenderer.render(matrixItem, holder.avatarImageView)
        holder.avatarDecorationImageView.setImageResource(userEncryptionTrustLevel.toImageRes())
    }
}
