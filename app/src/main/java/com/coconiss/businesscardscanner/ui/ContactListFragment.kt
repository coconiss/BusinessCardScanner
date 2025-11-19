package com.coconiss.businesscardscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.coconiss.businesscardscanner.R
import com.coconiss.businesscardscanner.adapter.ContactAdapter
import com.coconiss.businesscardscanner.data.ContactRepository
import com.coconiss.businesscardscanner.databinding.FragmentContactListBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactListFragment : Fragment() {

    private var _binding: FragmentContactListBinding? = null
    private val binding get() = _binding!!

    private lateinit var contactRepository: ContactRepository
    private lateinit var contactAdapter: ContactAdapter

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadContacts()
        } else {
            // 권한 거부 시 빈 화면만 표시
            showEmptyState("연락처 권한이 필요합니다.\n설정에서 권한을 허용해주세요.")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Repository 초기화
        contactRepository = ContactRepository(requireContext())

        // RecyclerView 설정
        contactAdapter = ContactAdapter(emptyList())
        binding.contactRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = contactAdapter
        }

        // 초기 상태: 빈 화면 표시 (크래시 방지)
        showEmptyState("저장된 연락처가 없습니다.\n명함을 스캔해보세요!")

        // FAB 클릭 이벤트
        binding.fabScan.setOnClickListener {
            findNavController().navigate(R.id.action_contactList_to_camera)
        }

        // 권한 확인 후 연락처 로드
        checkPermissionAndLoadContacts()
    }

    override fun onResume() {
        super.onResume()
        // onResume에서는 권한이 이미 허용된 경우만 로드
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            loadContacts()
        }
    }

    private fun checkPermissionAndLoadContacts() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED -> {
                // 권한이 이미 허용되어 있으면 바로 로드
                loadContacts()
            }
            else -> {
                // 권한 요청
                requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }

    private fun loadContacts() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 권한 다시 확인 (안전장치)
                if (ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.READ_CONTACTS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    withContext(Dispatchers.Main) {
                        showEmptyState("연락처 권한이 필요합니다.")
                    }
                    return@launch
                }

                val contacts = contactRepository.getAllContacts()

                withContext(Dispatchers.Main) {
                    if (contacts.isEmpty()) {
                        showEmptyState("저장된 연락처가 없습니다.\n명함을 스캔해보세요!")
                    } else {
                        binding.emptyText.visibility = View.GONE
                        binding.contactRecyclerView.visibility = View.VISIBLE
                        contactAdapter.updateContacts(contacts)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    showEmptyState("연락처 로드 실패\n다시 시도해주세요.")
                    Toast.makeText(requireContext(), "오류: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showEmptyState(message: String) {
        binding.emptyText.visibility = View.VISIBLE
        binding.emptyText.text = message
        binding.contactRecyclerView.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}