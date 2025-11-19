package com.coconiss.businesscardscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.coconiss.businesscardscanner.R
import com.coconiss.businesscardscanner.data.Contact
import com.coconiss.businesscardscanner.data.ContactRepository
import com.coconiss.businesscardscanner.databinding.FragmentEditContactBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditContactFragment : Fragment() {

    private var _binding: FragmentEditContactBinding? = null
    private val binding get() = _binding!!

    private lateinit var contact: Contact
    private lateinit var contactRepository: ContactRepository

    private val requestContactPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            saveContact()
        } else {
            Toast.makeText(requireContext(), "연락처 쓰기 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEditContactBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        contactRepository = ContactRepository(requireContext())

        // Bundle에서 Contact 받기
        contact = arguments?.getParcelable("contact") ?: Contact()

        // UI에 데이터 표시
        displayContact()

        // 버튼 클릭 이벤트
        binding.btnSave.setOnClickListener {
            updateContactFromUI()
            checkPermissionAndSaveContact()
        }

        binding.btnCancel.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun displayContact() {
        binding.nameEdit.setText(contact.name)
        binding.phoneEdit.setText(contact.phoneNumber)
        binding.companyEdit.setText(contact.company)
        binding.emailEdit.setText(contact.email)
        binding.addressEdit.setText(contact.address)

        // 이미지 표시
        if (contact.imageUri != null) {
            try {
                val bitmap = BitmapFactory.decodeFile(contact.imageUri)
                binding.imageView.setImageBitmap(bitmap)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateContactFromUI() {
        contact.name = binding.nameEdit.text.toString().trim()
        contact.phoneNumber = binding.phoneEdit.text.toString().trim()
        contact.company = binding.companyEdit.text.toString().trim()
        contact.email = binding.emailEdit.text.toString().trim()
        contact.address = binding.addressEdit.text.toString().trim()
    }

    private fun checkPermissionAndSaveContact() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.WRITE_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED -> {
                saveContact()
            }
            else -> {
                requestContactPermissionLauncher.launch(Manifest.permission.WRITE_CONTACTS)
            }
        }
    }

    private fun saveContact() {
        // 유효성 검사
        if (contact.name.isEmpty()) {
            Toast.makeText(requireContext(), "이름을 입력해주세요", Toast.LENGTH_SHORT).show()
            binding.nameEdit.requestFocus()
            return
        }

        if (contact.phoneNumber.isEmpty()) {
            Toast.makeText(requireContext(), "전화번호를 입력해주세요", Toast.LENGTH_SHORT).show()
            binding.phoneEdit.requestFocus()
            return
        }

        // 저장 진행
        binding.btnSave.isEnabled = false
        binding.btnCancel.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val success = contactRepository.saveContact(contact)

                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(requireContext(), "연락처가 저장되었습니다", Toast.LENGTH_SHORT).show()
                        // ContactListFragment로 돌아가기
                        findNavController().popBackStack(
                            R.id.contactListFragment,
                            false
                        )
                    } else {
                        Toast.makeText(requireContext(), "저장에 실패했습니다", Toast.LENGTH_SHORT).show()
                        binding.btnSave.isEnabled = true
                        binding.btnCancel.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "저장 중 오류 발생: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.btnSave.isEnabled = true
                    binding.btnCancel.isEnabled = true
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}