package com.coconiss.businesscardscanner.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Contact(
    var name: String = "",
    var phoneNumber: String = "",
    var company: String = "",
    var position: String = "",
    var email: String = "",
    var address: String = "",
    var imageUri: String? = null
) : Parcelable {
    fun getDisplayName(): String {
        val displayName = StringBuilder(name)
        if (position.isNotEmpty()) {
            displayName.append(" $position")
        }
        if (company.isNotEmpty()) {
            displayName.append(" ($company)")
        }
        return displayName.toString()
    }

    fun isValid(): Boolean {
        return name.isNotEmpty() && phoneNumber.isNotEmpty()
    }
}