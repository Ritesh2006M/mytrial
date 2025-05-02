package com.example.mytrial.ui.dashboard

import android.Manifest
import android.app.DatePickerDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.CalendarContract
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.mytrial.databinding.FragmentDashboardBinding
import java.text.SimpleDateFormat
import java.util.*

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val PERMISSION_REQUEST_CODE = 100
    private var selectedDate: Calendar = Calendar.getInstance()
    private var calendarList = mutableListOf<Pair<Long, String>>() // (id, name)
    private var selectedCalendarId: Long? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)

        binding.editDate.setOnClickListener {
            val today = Calendar.getInstance()
            DatePickerDialog(requireContext(),
                { _, year, month, day ->
                    selectedDate.set(year, month, day)
                    binding.editDate.setText(SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(selectedDate.time))
                },
                today.get(Calendar.YEAR),
                today.get(Calendar.MONTH),
                today.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        setupTimeAutoFormatter(binding.editStartTime)
        setupTimeAutoFormatter(binding.editEndTime)

        binding.btnCreateEvent.setOnClickListener {
            if (hasCalendarPermission()) {
                insertEventSilently()
            } else {
                requestCalendarPermission()
            }
        }

        if (hasCalendarPermission()) loadCalendars() else requestCalendarPermission()

        return binding.root
    }

    private fun hasCalendarPermission(): Boolean {
        val ctx = requireContext()
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCalendarPermission() {
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(
                Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.READ_CALENDAR
            ),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            loadCalendars()
        } else {
            Toast.makeText(requireContext(), "Calendar permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadCalendars() {
        calendarList.clear()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1"

        val cursor: Cursor? = requireContext().contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            null,
            null
        )

        val names = mutableListOf<String>()
        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val name = it.getString(1)
                calendarList.add(id to name)
                names.add(name)
            }
        }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, names)
        binding.calendarSpinner.adapter = adapter

        // Save selected ID
        binding.calendarSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                selectedCalendarId = calendarList.getOrNull(position)?.first
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {
                selectedCalendarId = null
            }
        }


        // Default selection
        if (calendarList.isNotEmpty()) {
            selectedCalendarId = calendarList[0].first
        }
    }

    private fun insertEventSilently() {
        try {
            val title = binding.editTitle.text.toString()
            val description = binding.editDescription.text.toString()
            val location = binding.editLocation.text.toString()
            val startTime = binding.editStartTime.text.toString()
            val endTime = binding.editEndTime.text.toString()

            if (title.isBlank() || startTime.length != 5 || endTime.length != 5 || selectedCalendarId == null) {
                Toast.makeText(requireContext(), "Fill all fields and select a calendar", Toast.LENGTH_SHORT).show()
                return
            }

            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val start = sdf.parse(startTime)
            val end = sdf.parse(endTime)

            val startMillis = Calendar.getInstance().apply {
                time = start!!
                set(Calendar.YEAR, selectedDate.get(Calendar.YEAR))
                set(Calendar.MONTH, selectedDate.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, selectedDate.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis

            val endMillis = Calendar.getInstance().apply {
                time = end!!
                set(Calendar.YEAR, selectedDate.get(Calendar.YEAR))
                set(Calendar.MONTH, selectedDate.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, selectedDate.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis

            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.CALENDAR_ID, selectedCalendarId!!)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }

            val uri = requireContext().contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            Toast.makeText(requireContext(), if (uri != null) "Event added!" else "Insert failed", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTimeAutoFormatter(editText: android.widget.EditText) {
        editText.addTextChangedListener(object : TextWatcher {
            var isEditing = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isEditing) return
                isEditing = true
                val digits = s.toString().replace(":", "").take(4)
                val formatted = when (digits.length) {
                    3, 4 -> digits.chunked(2).joinToString(":")
                    else -> digits
                }
                editText.setText(formatted)
                editText.setSelection(formatted.length.coerceAtMost(editText.text.length))
                isEditing = false
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
