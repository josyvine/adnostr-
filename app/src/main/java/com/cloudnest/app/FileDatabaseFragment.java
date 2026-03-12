package com.cloudnest.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cloudnest.app.databinding.FragmentFileDatabaseBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Fragment for the Visual Cloud Ledger.
 * Allows users to select a Drive and view an expandable list of 
 * Preset Folders and their associated file metrics.
 */
public class FileDatabaseFragment extends Fragment {

    private FragmentFileDatabaseBinding binding;
    private CloudNestDatabase db;
    private LedgerExpandableAdapter adapter;
    private List<DriveAccountEntity> driveList = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentFileDatabaseBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = CloudNestDatabase.getInstance(requireContext());

        setupRecyclerView();
        loadDrives();
    }

    private void setupRecyclerView() {
        binding.rvLedger.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new LedgerExpandableAdapter(requireContext(), new ArrayList<>());
        binding.rvLedger.setAdapter(adapter);
    }

    private void loadDrives() {
        db.driveAccountDao().getAllAccounts().observe(getViewLifecycleOwner(), accounts -> {
            if (accounts != null && !accounts.isEmpty()) {
                driveList = accounts;
                List<String> emails = new ArrayList<>();
                for (DriveAccountEntity acc : accounts) emails.add(acc.email);

                ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, emails);
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                binding.spinnerDriveSelector.setAdapter(spinnerAdapter);

                binding.spinnerDriveSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        loadLedgerData(driveList.get(position).email);
                    }
                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {}
                });
            }
        });
    }

    private void loadLedgerData(String email) {
        db.fileTrackDao().getLedgerReportForDrive(email).observe(getViewLifecycleOwner(), ledgerReports -> {
            if (ledgerReports != null) {
                adapter.updateList(ledgerReports);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
