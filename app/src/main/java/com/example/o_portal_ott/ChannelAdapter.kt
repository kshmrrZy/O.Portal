package com.example.o_portal_ott

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class ChannelAdapter(
    private val channels: List<Channel>, // Используем нашу модель данных
    private val epgData: Map<String, List<Program>>, // Передаем данные EPG
    private val onChannelClick: (Int) -> Unit // Передаем индекс для переключения
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    class ChannelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // Убедись, что эти ID совпадают с именами в item_channel.xml
        val ivLogo: ImageView = view.findViewById(R.id.itemLogo)
        val tvName: TextView = view.findViewById(R.id.itemName)
        val tvEpg: TextView = view.findViewById(R.id.itemEpg)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = channels[position]

        // 1. Имя канала
        holder.tvName.text = channel.name

        // 2. Текущая программа из EPG
        val programs = epgData[channel.tvgId?.lowercase()] ?: epgData[channel.name.lowercase().trim()]
        val currentProgram = programs?.find { System.currentTimeMillis() in it.start until it.stop }
        holder.tvEpg.text = currentProgram?.title ?: "Нет программы"

        // 3. Загрузка логотипа
        Glide.with(holder.itemView.context)
            .load(channel.logoFromEpg ?: channel.logoFromPlaylist)
            .into(holder.ivLogo)

        // Клик по карточке
        holder.itemView.setOnClickListener {
            onChannelClick(position)
        }

        // Эффект фокуса для пульта
        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                // Твой градиент карточки уже есть, добавим только рамку или легкую подсветку
                view.setBackgroundResource(R.drawable.bg_channel_card_gradient) // Перерисовываем фон
                view.alpha = 1.0f
                view.scaleX = 1.05f
                view.scaleY = 1.05f
            } else {
                view.alpha = 0.8f
                view.scaleX = 1.0f
                view.scaleY = 1.0f
            }
        }
    }

    override fun getItemCount() = channels.size
}