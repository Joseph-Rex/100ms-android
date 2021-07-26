package live.hms.app2.ui.meeting.participants

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import live.hms.app2.R
import live.hms.app2.databinding.FragmentParticipantsBinding
import live.hms.app2.model.RoomDetails
import live.hms.app2.ui.meeting.MeetingViewModel
import live.hms.app2.ui.meeting.MeetingViewModelFactory
import live.hms.app2.util.ROOM_DETAILS
import live.hms.app2.util.viewLifecycle
import live.hms.video.sdk.models.HMSRemotePeer
import live.hms.video.sdk.models.role.HMSRole

class ParticipantsFragment : Fragment() {


  private var binding by viewLifecycle<FragmentParticipantsBinding>()

  private val meetingViewModel: MeetingViewModel by activityViewModels {
    MeetingViewModelFactory(
      requireActivity().application,
      requireActivity().intent!!.extras!![ROOM_DETAILS] as RoomDetails
    )
  }

  lateinit var adapter: ParticipantsAdapter

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    binding = FragmentParticipantsBinding.inflate(inflater, container, false)
    initViewModels()
    return binding.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    adapter =
      ParticipantsAdapter(meetingViewModel.isAllowedToChangeRole(),
        meetingViewModel.getAvailableRoles(),
        this::showDialog)
    initViews()
  }

  private var alertDialog: AlertDialog? = null

  private fun showDialog(remotePeer: HMSRemotePeer, toRole: HMSRole) {
    val builder = AlertDialog.Builder(requireContext())
      .setMessage("Changing role of \"${remotePeer.name}\" from ${remotePeer.hmsRole.name} to ${toRole.name}")
      .setTitle(R.string.role_change)
      .setCancelable(false)

    builder.setPositiveButton("Request Change") { dialog, _ ->
      meetingViewModel.changeRole(remotePeer, toRole, false)
      dialog.dismiss()
      alertDialog = null
    }

    builder.setNegativeButton("Force Change") { dialog, _ ->
      meetingViewModel.changeRole(remotePeer, toRole, true)
      dialog.dismiss()
      alertDialog = null
    }

    alertDialog = builder.create().apply { show() }
  }

  private fun initViews() {
    binding.participantCount.text = "0"
    binding.recyclerView.apply {
      layoutManager = LinearLayoutManager(requireContext())
      adapter = this@ParticipantsFragment.adapter
    }

    binding.textInputSearch.apply {
      addTextChangedListener { text ->
        val items = meetingViewModel
          .peers
          .filter { text.isNullOrEmpty() || it.name.contains(text.toString(), true) }
          .toTypedArray()
        adapter.setItems(items)
      }
    }
  }

  private fun initViewModels() {
    meetingViewModel.peerLiveDate.observe(viewLifecycleOwner) {
      val peers = meetingViewModel.peers
      adapter.setItems(peers)
      binding.participantCount.text = "${peers.size}"
    }
  }

}