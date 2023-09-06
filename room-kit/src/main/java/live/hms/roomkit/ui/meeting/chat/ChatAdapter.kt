package live.hms.roomkit.ui.meeting.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import live.hms.roomkit.R
import live.hms.roomkit.databinding.ListItemChatBinding
import live.hms.roomkit.ui.theme.applyTheme
import java.text.SimpleDateFormat
import java.util.*


class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.ChatMessageViewHolder>(DIFFUTIL_CALLBACK) {
  companion object {
    private val DIFFUTIL_CALLBACK = object : DiffUtil.ItemCallback<ChatMessage>() {
      override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
        oldItem == newItem


      override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
        oldItem == newItem
    }
  }

  inner class ChatMessageViewHolder(val binding: ListItemChatBinding) :
    RecyclerView.ViewHolder(binding.root) {
    init {
        binding.applyTheme()
    }

    fun bind(message: ChatMessage) {
      binding.name.text = "${message.senderName}${getRecipientText(message)}"
      binding.message.text = message.message
      binding.blueBar.visibility = if (message.isSentByMe) View.VISIBLE else View.GONE
    }

    private fun getRecipientText(message: ChatMessage): String =
      when(message.recipient) {
        Recipient.Everyone -> ""
        is Recipient.Peer -> " (to ${message.recipient.peer.name})"
        is Recipient.Role -> " (to ${message.recipient.role.name})"
      }
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMessageViewHolder {
    val binding = ListItemChatBinding.inflate(
      LayoutInflater.from(parent.context),
      parent,
      false
    )
    return ChatMessageViewHolder(binding)
  }

  override fun onBindViewHolder(holder: ChatMessageViewHolder, position: Int) {
    holder.bind(getItem(position))
  }

  override fun onBindViewHolder(
    holder: ChatMessageViewHolder,
    position: Int,
    payloads: MutableList<Any>
  ) {
    super.onBindViewHolder(holder, position, payloads)
    // Skip doing anything maybe it just relayouts
  }
}