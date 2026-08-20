package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter

/** An exposed-dropdown adapter whose choices are never narrowed by the displayed selection. */
class SelectionArrayAdapter(
    context: Context,
    items: List<String>,
) : ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, items.toMutableList()) {
    private val allItems = items.toList()

    private val selectionFilter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults = FilterResults().apply {
            values = allItems
            count = allItems.size
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            setNotifyOnChange(false)
            clear()
            addAll(allItems)
            notifyDataSetChanged()
        }

        override fun convertResultToString(resultValue: Any?): CharSequence = resultValue?.toString().orEmpty()
    }

    override fun getFilter(): Filter = selectionFilter
}
