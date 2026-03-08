package com.cloudnest.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.cloudnest.app.databinding.FragmentDriveAccountsBinding;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.api.services.drive.DriveScopes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Multi-Account Management Screen.
 * Implements the "Drive Cycling" logic interface.
 * Allows adding multiple Google Accounts and selecting which one is "Active".
 * Displays storage status (Full/Available) for each linked account.
 */
public class DriveAccountsFragment extends Fragment implements DriveAccountAdapter.OnAccountClickListener {

    private static final String TAG = "DriveAccountsFragment";

    private FragmentDriveAccountsBinding binding;
    private DriveAccountAdapter adapter;
    private CloudNestDatabase db;
    private ExecutorService dbExecutor;

    private GoogleSignInClient googleSignInClient;
    private ActivityResultLauncher<Intent> addAccountLauncher;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentDriveAccountsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = CloudNestDatabase.getInstance(requireContext());
        dbExecutor = Executors.newSingleThreadExecutor();

        setupRecyclerView();
        setupGoogleSignIn();
        setupAddButton();
        observeAccounts();
    }

    private void setupRecyclerView() {
        binding.recyclerViewAccounts.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new DriveAccountAdapter(requireContext(), new ArrayList<>(), this);
        binding.recyclerViewAccounts.setAdapter(adapter);
    }

    /**
     * Configures Google Sign-In specifically for adding secondary accounts.
     * Note: We request email and Drive scopes.
     */
    private void setupGoogleSignIn() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE_FILE), new Scope(DriveScopes.DRIVE_METADATA))
                .build();

        googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso);

        addAccountLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        handleSignInResult(result.getData());
                    } else {
                        Toast.makeText(getContext(), "Account addition cancelled.", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private void setupAddButton() {
        binding.fabAddAccount.setOnClickListener(v -> {
            // Force account picker to show, allowing user to select a different account
            googleSignInClient.signOut().addOnCompleteListener(requireActivity(), task -> {
                Intent signInIntent = googleSignInClient.getSignInIntent();
                addAccountLauncher.launch(signInIntent);
            });
        });
    }

    /**
     * Observes the database for changes in the account list.
     */
    private void observeAccounts() {
        db.driveAccountDao().getAllAccounts().observe(getViewLifecycleOwner(), new Observer<List<DriveAccountEntity>>() {
            @Override
            public void onChanged(List<DriveAccountEntity> accounts) {
                if (accounts == null || accounts.isEmpty()) {
                    binding.tvNoAccounts.setVisibility(View.VISIBLE);
                    binding.recyclerViewAccounts.setVisibility(View.GONE);
                } else {
                    binding.tvNoAccounts.setVisibility(View.GONE);
                    binding.recyclerViewAccounts.setVisibility(View.VISIBLE);
                    adapter.updateList(accounts);
                }
            }
        });
    }

    /**
     * Process the result from Google Sign-In.
     * Saves the new account to the Room Database.
     */
    private void handleSignInResult(Intent data) {
        try {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            GoogleSignInAccount account = task.getResult(ApiException.class);

            if (account != null) {
                String email = account.getEmail();
                String name = account.getDisplayName();
                
                // Save to Database
                saveAccountToDb(email, name);
            }
        } catch (ApiException e) {
            Log.e(TAG, "signInResult:failed code=" + e.getStatusCode());
            Toast.makeText(getContext(), "Failed to add account.", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveAccountToDb(String email, String name) {
        dbExecutor.execute(() -> {
            // Check if already exists
            DriveAccountEntity existing = db.driveAccountDao().getAccountByEmail(email);
            if (existing != null) {
                requireActivity().runOnUiThread(() -> 
                    Toast.makeText(getContext(), "Account already connected!", Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Create new Entity
            DriveAccountEntity newAccount = new DriveAccountEntity();
            newAccount.email = email;
            newAccount.displayName = name != null ? name : "Google Drive User";
            newAccount.isActive = false; // Default to inactive, user must switch manually or auto-cycle
            newAccount.isFull = false;
            newAccount.totalSpace = 0; // Will be updated by API later
            newAccount.usedSpace = 0;

            // If it's the ONLY account, make it active by default
            if (db.driveAccountDao().getAccountCount() == 0) {
                newAccount.isActive = true;
            }

            db.driveAccountDao().insert(newAccount);

            requireActivity().runOnUiThread(() -> 
                Toast.makeText(getContext(), "Account Added: " + email, Toast.LENGTH_SHORT).show()
            );
        });
    }

    // --- Adapter Interaction ---

    @Override
    public void onAccountClick(DriveAccountEntity account) {
        // Switch Active Account Logic
        new AlertDialog.Builder(requireContext())
                .setTitle("Switch Active Drive?")
                .setMessage("Set " + account.email + " as the primary upload destination?")
                .setPositiveButton("Set Active", (dialog, which) -> {
                    setActiveAccount(account.email);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onRemoveClick(DriveAccountEntity account) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Remove Account?")
                .setMessage("Disconnect " + account.email + "?\n(Files on Drive will remain safe)")
                .setPositiveButton("Remove", (dialog, which) -> {
                    dbExecutor.execute(() -> db.driveAccountDao().delete(account));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Updates the database to set one account as active and all others as inactive.
     */
    private void setActiveAccount(String targetEmail) {
        dbExecutor.execute(() -> {
            db.driveAccountDao().resetAllActive();
            db.driveAccountDao().setActive(targetEmail, true);
            
            requireActivity().runOnUiThread(() -> 
                Toast.makeText(getContext(), "Active Drive Updated.", Toast.LENGTH_SHORT).show()
            );
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (dbExecutor != null) dbExecutor.shutdown();
        binding = null;
    }
}