package com.coconiss.businesscardscanner.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.coconiss.businesscardscanner.R
import com.coconiss.businesscardscanner.data.Contact

class ContactAdapter(
    private var contacts: List<Contact>
) : RecyclerView.Adapter<ContactAdapter.ContactViewHolder>() {

    class ContactViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val contactImage: ImageView = view.findViewById(R.id.contactImage)
        val contactName: TextView = view.findViewById(R.id.contactName)
        val contactPhone: TextView = view.findViewById(R.id.contactPhone)
        val contactEmail: TextView = view.findViewById(R.id.contactEmail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]

        holder.contactName.text = contact.getDisplayName()
        holder.contactPhone.text = contact.phoneNumber

        if (contact.email.isNotEmpty()) {
            holder.contactEmail.text = contact.email
            holder.contactEmail.visibility = View.VISIBLE
        } else {
            holder.contactEmail.visibility = View.GONE
        }

        // 이미지 설정
        if (contact.imageUri != null) {
            try {
                holder.contactImage.setImageURI(Uri.parse(contact.imageUri))
            } catch (e: Exception) {
                holder.contactImage.setImageResource(R.drawable.ic_launcher_foreground)
            }
        } else {
            holder.contactImage.setImageResource(R.drawable.ic_launcher_foreground)
        }
    }

    override fun getItemCount() = contacts.size

    fun updateContacts(newContacts: List<Contact>) {
        contacts = newContacts
        notifyDataSetChanged()
    }
}