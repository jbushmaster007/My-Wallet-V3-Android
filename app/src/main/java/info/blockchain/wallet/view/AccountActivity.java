package info.blockchain.wallet.view;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.android.CaptureActivity;
import com.google.zxing.client.android.Intents;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.AppCompatEditText;
import android.support.v7.widget.LinearLayoutManager;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.getbase.floatingactionbutton.FloatingActionButton;
import com.getbase.floatingactionbutton.FloatingActionsMenu;

import info.blockchain.api.AddressInfo;
import info.blockchain.wallet.account_manager.AccountAdapter;
import info.blockchain.wallet.account_manager.AccountItem;
import info.blockchain.wallet.connectivity.ConnectivityStatus;
import info.blockchain.wallet.model.PendingTransaction;
import info.blockchain.wallet.multiaddr.MultiAddrFactory;
import info.blockchain.wallet.payload.Account;
import info.blockchain.wallet.payload.ImportedAccount;
import info.blockchain.wallet.payload.LegacyAddress;
import info.blockchain.wallet.payload.Payload;
import info.blockchain.wallet.payload.PayloadBridge;
import info.blockchain.wallet.payload.PayloadManager;
import info.blockchain.wallet.util.AppUtil;
import info.blockchain.wallet.util.DoubleEncryptionFactory;
import info.blockchain.wallet.util.ExchangeRateFactory;
import info.blockchain.wallet.util.FormatsUtil;
import info.blockchain.wallet.util.MonetaryUtil;
import info.blockchain.wallet.util.PermissionUtil;
import info.blockchain.wallet.util.PrefsUtil;
import info.blockchain.wallet.util.PrivateKeyFactory;
import info.blockchain.wallet.util.ViewUtils;
import info.blockchain.wallet.view.customviews.MaterialProgressDialog;
import info.blockchain.wallet.view.helpers.RecyclerItemClickListener;
import info.blockchain.wallet.view.helpers.SecondPasswordHandler;
import info.blockchain.wallet.view.helpers.ToastCustom;
import info.blockchain.wallet.viewModel.AccountViewModel;
import info.blockchain.wallet.websocket.WebSocketService;

import org.bitcoinj.core.Base58;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.crypto.BIP38PrivateKey;
import org.bitcoinj.params.MainNetParams;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import javax.annotation.Nullable;

import piuk.blockchain.android.BaseAuthActivity;
import piuk.blockchain.android.BuildConfig;
import piuk.blockchain.android.R;
import piuk.blockchain.android.annotations.Thunk;
import piuk.blockchain.android.databinding.ActivityAccountsBinding;
import piuk.blockchain.android.databinding.AlertPromptTransferFundsBinding;
import piuk.blockchain.android.databinding.FragmentSendConfirmBinding;

public class AccountActivity extends BaseAuthActivity implements AccountViewModel.DataListener {

    private static final int IMPORT_PRIVATE_REQUEST_CODE = 2006;
    private static final int EDIT_ACTIVITY_REQUEST_CODE = 2007;

    private static int ADDRESS_LABEL_MAX_LENGTH = 17;

    private static String[] HEADERS;
    public static String IMPORTED_HEADER;

    private AppUtil appUtil;

    protected BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, final Intent intent) {
            if (BalanceFragment.ACTION_INTENT.equals(intent.getAction())) {
                AccountActivity.this.runOnUiThread(() -> onUpdateAccountsList());

            }
        }
    };
    private LinearLayoutManager layoutManager = null;
    private ArrayList<AccountItem> accountsAndImportedList = null;
    private AccountAdapter accountsAdapter = null;
    private ArrayList<Integer> headerPositions;
    private int hdAccountsIdx;
    private List<LegacyAddress> legacy = null;
    private MaterialProgressDialog progress = null;
    private Context context = null;
    private MenuItem transferFundsMenuItem = null;
    private PrefsUtil prefsUtil;
    private MonetaryUtil monetaryUtil;
    private PayloadManager payloadManager;

    private ActivityAccountsBinding binding;
    private String secondPassword;

    @Thunk AccountViewModel viewModel;
    private AlertDialog transactionSuccessDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        context = this;
        prefsUtil = new PrefsUtil(context);
        appUtil = new AppUtil(context);
        payloadManager = PayloadManager.getInstance();
        monetaryUtil = new MonetaryUtil(prefsUtil.getValue(PrefsUtil.KEY_BTC_UNITS, MonetaryUtil.UNIT_BTC));

        binding = DataBindingUtil.setContentView(this, R.layout.activity_accounts);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        viewModel = new AccountViewModel(this, this);

        initToolbar();

        setupViews();

        setFab();
    }

    private void initToolbar(){

        if (!payloadManager.isNotUpgraded()) {
            binding.toolbarContainer.toolbarGeneral.setTitle("");//TODO - empty header for V3 for now - awaiting product
        } else {
            binding.toolbarContainer.toolbarGeneral.setTitle(getResources().getString(R.string.my_addresses));
        }
        setSupportActionBar(binding.toolbarContainer.toolbarGeneral);
    }

    private void setupViews(){

        IMPORTED_HEADER = getResources().getString(R.string.imported_addresses);

        if (!payloadManager.isNotUpgraded())
            HEADERS = new String[]{IMPORTED_HEADER};
        else
            HEADERS = new String[0];

        layoutManager = new LinearLayoutManager(this);
        binding.accountsList.setLayoutManager(layoutManager);

        accountsAndImportedList = new ArrayList<>();
        onUpdateAccountsList();
        accountsAdapter = new AccountAdapter(accountsAndImportedList, this);
        binding.accountsList.setAdapter(accountsAdapter);

        binding.accountsList.addOnItemTouchListener(
                new RecyclerItemClickListener(this, new RecyclerItemClickListener.OnItemClickListener() {

                    @Override
                    public void onItemClick(final View view, int position) {

                        if (!payloadManager.isNotUpgraded())
                            if (headerPositions.contains(position)) return;//headers unclickable

                        onRowClick(position);
                    }
                })
        );

        binding.balanceMainContentShadow.setOnClickListener(view -> binding.multipleActions.collapse());
    }

    private void setFab(){

        //First icon when fab expands
        FloatingActionButton actionA = new FloatingActionButton(getBaseContext());
        actionA.setColorNormal(getResources().getColor(R.color.blockchain_transfer_blue));
        actionA.setSize(FloatingActionButton.SIZE_MINI);
        actionA.setIconDrawable(getResources().getDrawable(R.drawable.icon_accounthd));
        actionA.setColorPressed(getResources().getColor(R.color.blockchain_dark_blue));

        if (!payloadManager.isNotUpgraded()) {
            //V3
            actionA.setTitle(getResources().getString(R.string.create_new));
            actionA.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    createNewAccount();
                    if(binding.multipleActions.isExpanded())
                        binding.multipleActions.collapse();
                }
            });
        }else {
            //V2
            actionA.setTitle(getResources().getString(R.string.create_new_address));
            actionA.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if(binding.multipleActions.isExpanded())
                        binding.multipleActions.collapse();
                    createNewAddress();
                }
            });
        }

        //Second icon when fab expands
        FloatingActionButton actionB = new FloatingActionButton(getBaseContext());
        actionB.setColorNormal(getResources().getColor(R.color.blockchain_transfer_blue));
        actionB.setSize(FloatingActionButton.SIZE_MINI);
        actionB.setIconDrawable(getResources().getDrawable(R.drawable.icon_imported));
        actionB.setColorPressed(getResources().getColor(R.color.blockchain_dark_blue));
        actionB.setTitle(getResources().getString(R.string.import_address));
        actionB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(binding.multipleActions.isExpanded())
                    binding.multipleActions.collapse();
                importAddress();
            }
        });

        binding.multipleActions.setOnFloatingActionsMenuUpdateListener(new FloatingActionsMenu.OnFloatingActionsMenuUpdateListener() {
            @Override
            public void onMenuExpanded() {
                binding.balanceMainContentShadow.setVisibility(View.VISIBLE);
            }

            @Override
            public void onMenuCollapsed() {
                binding.balanceMainContentShadow.setVisibility(View.INVISIBLE);
            }
        });

        //Add buttons to expanding fab
        binding.multipleActions.addButton(actionA);
        binding.multipleActions.addButton(actionB);
    }

    @Override
    public void onBackPressed() {
        if (!isFinishing() && binding.multipleActions != null) {
            if (binding.multipleActions.isExpanded()) {
                binding.multipleActions.collapse();
            } else {
                super.onBackPressed();
            }
        }
    }

    private void onRowClick(int position){

        Intent intent = new Intent(this, AccountEditActivity.class);
        if (position - HEADERS.length >= hdAccountsIdx) {//2 headers before imported
            intent.putExtra("address_index", position - HEADERS.length - hdAccountsIdx);
        } else {
            intent.putExtra("account_index", position);
        }
        startActivityForResult(intent, EDIT_ACTIVITY_REQUEST_CODE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.account_activity_actions, menu);

        transferFundsMenuItem = menu.findItem(R.id.action_transfer_funds);

        viewModel.checkTransferableLegacyFunds(true);//Auto popup

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
            case R.id.action_transfer_funds:
                onShowProgressDialog(getString(R.string.app),getString(R.string.please_wait));
                viewModel.checkTransferableLegacyFunds(false);//Not auto popup
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void startScanActivity(){
        if (!appUtil.isCameraOpen()) {
            Intent intent = new Intent(AccountActivity.this, CaptureActivity.class);
            intent.putExtra(Intents.Scan.FORMATS, EnumSet.allOf(BarcodeFormat.class));
            intent.putExtra(Intents.Scan.MODE, Intents.Scan.QR_CODE_MODE);
            startActivityForResult(intent, IMPORT_PRIVATE_REQUEST_CODE);
        } else {
            ToastCustom.makeText(AccountActivity.this, getString(R.string.camera_unavailable), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
        }
    }

    private void importAddress() {
        if (ContextCompat.checkSelfPermission(AccountActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            PermissionUtil.requestCameraPermissionFromActivity(binding.mainLayout, AccountActivity.this);
        } else {
            new SecondPasswordHandler(AccountActivity.this).validate(new SecondPasswordHandler.ResultListener() {
                @Override
                public void onNoSecondPassword() {
                    startScanActivity();
                }

                @Override
                public void onSecondPasswordValidated(String validateSecondPassword) {
                    secondPassword = validateSecondPassword;
                    startScanActivity();
                }
            });
        }
    }

    private void createNewAccount() {

        new SecondPasswordHandler(AccountActivity.this).validate(new SecondPasswordHandler.ResultListener() {
            @Override
            public void onNoSecondPassword() {
                promptForAccountLabel(null);
            }

            @Override
            public void onSecondPasswordValidated(String validateSecondPassword) {
                secondPassword = validateSecondPassword;
                promptForAccountLabel(validateSecondPassword);
            }
        });
    }

    private void promptForAccountLabel(@Nullable final String validatedSecondPassword){
        final AppCompatEditText etLabel = new AppCompatEditText(this);
        etLabel.setInputType(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        etLabel.setFilters(new InputFilter[]{new InputFilter.LengthFilter(ADDRESS_LABEL_MAX_LENGTH)});

        FrameLayout frameLayout = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int marginInPixels = (int) ViewUtils.convertDpToPixel(20, this);
        params.setMargins(marginInPixels, 0, marginInPixels, 0);
        frameLayout.addView(etLabel, params);

        new AlertDialog.Builder(this, R.style.AlertDialogStyle)
                .setTitle(R.string.label)
                .setMessage(R.string.assign_display_name)
                .setView(frameLayout)
                .setCancelable(false)
                .setPositiveButton(R.string.save_name, (dialog, whichButton) -> {

                    if (etLabel != null && etLabel.getText().toString().trim().length() > 0) {
                        addAccount(etLabel.getText().toString().trim(), validatedSecondPassword);
                    } else {
                        ToastCustom.makeText(AccountActivity.this, getResources().getString(R.string.label_cant_be_empty), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void addAccount(final String accountLabel, @Nullable final String secondPassword) {

        if (!ConnectivityStatus.hasConnectivity(AccountActivity.this)) {
            ToastCustom.makeText(AccountActivity.this, getString(R.string.check_connectivity_exit), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
        } else {

            new AsyncTask<Void, Void, Void>() {

                MaterialProgressDialog progress;

                @Override
                protected void onPreExecute() {
                    super.onPreExecute();

                    if (progress != null && progress.isShowing()) {
                        progress.dismiss();
                        progress = null;
                    }
                    progress = new MaterialProgressDialog(AccountActivity.this);
                    progress.setMessage(getString(R.string.please_wait));
                    progress.show();
                }

                @Override
                protected void onPostExecute(Void aVoid) {
                    super.onPostExecute(aVoid);
                    if (progress != null && progress.isShowing()) {
                        progress.dismiss();
                        progress = null;
                    }
                }

                @Override
                protected Void doInBackground(Void... params) {

                    try {
                        payloadManager.addAccount(accountLabel, secondPassword, new PayloadManager.AccountAddListener() {
                            @Override
                            public void onAccountAddSuccess(Account account) {
                                ToastCustom.makeText(AccountActivity.this,
                                        AccountActivity.this.getString(R.string.remote_save_ok),
                                        ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_OK);

                                //Subscribe to new xpub only if successfully created
                                Intent intent = new Intent(WebSocketService.ACTION_INTENT);
                                intent.putExtra("xpub", account.getXpub());
                                LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

                                //Update adapter list
                                onUpdateAccountsList();
                            }

                            @Override
                            public void onSecondPasswordFail() {
                                ToastCustom.makeText(AccountActivity.this,
                                        AccountActivity.this.getString(R.string.double_encryption_password_error),
                                        ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                            }

                            @Override
                            public void onPayloadSaveFail() {
                                ToastCustom.makeText(AccountActivity.this,
                                        AccountActivity.this.getString(R.string.remote_save_ko),
                                        ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);

                            }
                        });
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                    return null;
                }
            }.execute();
        }
    }

    private void createNewAddress(){

        if (!ConnectivityStatus.hasConnectivity(AccountActivity.this)) {
            ToastCustom.makeText(AccountActivity.this, getString(R.string.check_connectivity_exit), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
        } else {

            new SecondPasswordHandler(AccountActivity.this).validate(new SecondPasswordHandler.ResultListener() {
                @Override
                public void onNoSecondPassword() {
                    addAddress();
                }

                @Override
                public void onSecondPasswordValidated(String validateSecondPassword) {
                    secondPassword = validateSecondPassword;
                    addAddress();
                }
            });
        }

    }

    @Override
    public void onUpdateAccountsList() {

        headerPositions = new ArrayList<Integer>();

        //accountsAndImportedList is linked to AccountAdapter - do not reconstruct or loose reference otherwise notifyDataSetChanged won't work
        accountsAndImportedList.clear();

        int i = 0;
        if (payloadManager.getPayload().isUpgraded()) {

            List<Account> accounts = payloadManager.getPayload().getHdWallet().getAccounts();
            List<Account> accountClone = new ArrayList<Account>(accounts.size());
            accountClone.addAll(accounts);

            if (accountClone.get(accountClone.size() - 1) instanceof ImportedAccount) {
                accountClone.remove(accountClone.size() - 1);
            }

            int defaultIndex = payloadManager.getPayload().getHdWallet().getDefaultIndex();
            Account defaultAccount = payloadManager.getPayload().getHdWallet().getAccounts().get(defaultIndex);

            int archivedCount = 0;
            for (; i < accountClone.size(); i++) {

                String label = accountClone.get(i).getLabel();
                String balance = getAccountBalance(i);

                if (label != null && label.length() > ADDRESS_LABEL_MAX_LENGTH) label = label.substring(0, ADDRESS_LABEL_MAX_LENGTH)+"...";
                if (label == null || label.length() == 0) label = "";

                accountsAndImportedList.add(new AccountItem(label, null, balance, getResources().getDrawable(R.drawable.icon_accounthd), accountClone.get(i).isArchived(), false, defaultAccount.getXpub().equals(accountClone.get(i).getXpub())));
            }
            hdAccountsIdx = accountClone.size() - archivedCount;
        }

        ImportedAccount iAccount = null;
        if (payloadManager.getPayload().getLegacyAddresses().size() > 0) {
            iAccount = new ImportedAccount(getString(R.string.imported_addresses), payloadManager.getPayload().getLegacyAddresses(), new ArrayList<String>(), MultiAddrFactory.getInstance().getLegacyBalance());
        }
        if (iAccount != null) {

            if (!payloadManager.isNotUpgraded()) {
                //Imported Header Position
                headerPositions.add(accountsAndImportedList.size());
                accountsAndImportedList.add(new AccountItem(HEADERS[0], null, "", getResources().getDrawable(R.drawable.icon_accounthd), false, false, false));
            }

            legacy = iAccount.getLegacyAddresses();
            for (int j = 0; j < legacy.size(); j++) {

                String label = legacy.get(j).getLabel();
                String address = legacy.get(j).getAddress();
                String balance = getAddressBalance(j);

                if (label != null && label.length() > ADDRESS_LABEL_MAX_LENGTH) label = label.substring(0, ADDRESS_LABEL_MAX_LENGTH)+"...";
                if (label == null || label.length() == 0) label = "";
                if (address == null || address.length() == 0) address = "";

                accountsAndImportedList.add(new AccountItem(label, address, balance, getResources().getDrawable(R.drawable.icon_imported), legacy.get(j).getTag() == PayloadManager.ARCHIVED_ADDRESS, legacy.get(j).isWatchOnly(), false));
            }
        }

        AccountActivity.this.runOnUiThread(() -> {
            if (accountsAdapter != null) {
                accountsAdapter.notifyDataSetChanged();
                binding.accountsList.setAdapter(accountsAdapter);
            }
        });
    }

    private String getAccountBalance(int index) {

        String address = payloadManager.getXpubFromAccountIndex(index);
        Long amount = MultiAddrFactory.getInstance().getXpubAmounts().get(address);
        if (amount == null) amount = 0L;

        String unit = (String) monetaryUtil.getBTCUnits()[prefsUtil.getValue(PrefsUtil.KEY_BTC_UNITS, MonetaryUtil.UNIT_BTC)];

        return monetaryUtil.getDisplayAmount(amount) + " " + unit;
    }

    private String getAddressBalance(int index) {

        String address = legacy.get(index).getAddress();
        Long amount = MultiAddrFactory.getInstance().getLegacyBalance(address);
        String unit = (String) monetaryUtil.getBTCUnits()[prefsUtil.getValue(PrefsUtil.KEY_BTC_UNITS, MonetaryUtil.UNIT_BTC)];

        return monetaryUtil.getDisplayAmount(amount) + " " + unit;
    }

    @Override
    protected void onResume() {
        super.onResume();

        IntentFilter filter = new IntentFilter(BalanceFragment.ACTION_INTENT);
        LocalBroadcastManager.getInstance(AccountActivity.this).registerReceiver(receiver, filter);

        onUpdateAccountsList();
    }

    @Override
    public void onPause() {
        LocalBroadcastManager.getInstance(AccountActivity.this).unregisterReceiver(receiver);
        super.onPause();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {

        if (resultCode == Activity.RESULT_OK && requestCode == IMPORT_PRIVATE_REQUEST_CODE
                && data != null && data.getStringExtra(CaptureActivity.SCAN_RESULT) != null) {
            try {
                final String strResult = data.getStringExtra(CaptureActivity.SCAN_RESULT);
                String format = PrivateKeyFactory.getInstance().getFormat(strResult);
                if (format != null) {
                    //Private key scanned
                    if (!format.equals(PrivateKeyFactory.BIP38)) {
                        importNonBIP38Address(format, strResult);
                    } else {
                        importBIP38Address(strResult);
                    }
                } else {
                    //Watch-only address scanned
                    importWatchOnly(strResult);
                }
            } catch (Exception e) {
                ToastCustom.makeText(AccountActivity.this, getString(R.string.privkey_error), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
            }
        } else if (resultCode == Activity.RESULT_CANCELED && requestCode == IMPORT_PRIVATE_REQUEST_CODE) {
            ;
        } else if (resultCode == Activity.RESULT_OK && requestCode == EDIT_ACTIVITY_REQUEST_CODE) {

            onUpdateAccountsList();

        } else if (resultCode == Activity.RESULT_CANCELED && requestCode == EDIT_ACTIVITY_REQUEST_CODE) {

        }
    }

    private void importBIP38Address(final String data) {

        final List<LegacyAddress> rollbackLegacyAddresses = payloadManager.getPayload().getLegacyAddresses();

        final AppCompatEditText password = new AppCompatEditText(this);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        FrameLayout frameLayout = new FrameLayout(AccountActivity.this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int marginInPixels = (int) ViewUtils.convertDpToPixel(20, AccountActivity.this);
        params.setMargins(marginInPixels, 0, marginInPixels, 0);
        frameLayout.addView(password, params);

        new AlertDialog.Builder(this, R.style.AlertDialogStyle)
                .setTitle(R.string.app_name)
                .setMessage(R.string.bip38_password_entry)
                .setView(frameLayout)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {

                        final String pw = password.getText().toString();

                        if (progress != null && progress.isShowing()) {
                            progress.dismiss();
                            progress = null;
                        }
                        progress = new MaterialProgressDialog(AccountActivity.this);
                        progress.setMessage(AccountActivity.this.getResources().getString(R.string.please_wait));
                        progress.show();

                        new Thread(new Runnable() {
                            @Override
                            public void run() {

                                Looper.prepare();

                                try {
                                    BIP38PrivateKey bip38 = new BIP38PrivateKey(MainNetParams.get(), data);
                                    final ECKey key = bip38.decrypt(pw);

                                    if (key != null && key.hasPrivKey() && payloadManager.getPayload().getLegacyAddressStrings().contains(key.toAddress(MainNetParams.get()).toString())) {

                                        //A private key to an existing address has been scanned
                                        setPrivateKey(key);

                                    } else if (key != null && key.hasPrivKey() && !payloadManager.getPayload().getLegacyAddressStrings().contains(key.toAddress(MainNetParams.get()).toString())) {
                                        final LegacyAddress legacyAddress = new LegacyAddress(null, System.currentTimeMillis() / 1000L, key.toAddress(MainNetParams.get()).toString(), "", 0L, "android", BuildConfig.VERSION_NAME);
                                                    /*
                                                     * if double encrypted, save encrypted in payload
                                                     */
                                        if (!payloadManager.getPayload().isDoubleEncrypted()) {
                                            legacyAddress.setEncryptedKey(key.getPrivKeyBytes());
                                        } else {
                                            String encryptedKey = Base58.encode(key.getPrivKeyBytes());
                                            String encrypted2 = DoubleEncryptionFactory.getInstance().encrypt(encryptedKey,
                                                    payloadManager.getPayload().getSharedKey(),
                                                    secondPassword,
                                                    payloadManager.getPayload().getOptions().getIterations());
                                            legacyAddress.setEncryptedKey(encrypted2);
                                        }

                                        final AppCompatEditText address_label = new AppCompatEditText(AccountActivity.this);
                                        address_label.setFilters(new InputFilter[]{new InputFilter.LengthFilter(ADDRESS_LABEL_MAX_LENGTH)});
                                        address_label.setInputType(InputType.TYPE_TEXT_FLAG_CAP_WORDS);

                                        FrameLayout frameLayout = new FrameLayout(AccountActivity.this);
                                        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                                        int marginInPixels = (int) ViewUtils.convertDpToPixel(20, AccountActivity.this);
                                        params.setMargins(marginInPixels, 0, marginInPixels, 0);
                                        frameLayout.addView(address_label, params);

                                        new AlertDialog.Builder(AccountActivity.this, R.style.AlertDialogStyle)
                                                .setTitle(R.string.app_name)
                                                .setMessage(R.string.label_address)
                                                .setView(frameLayout)
                                                .setCancelable(false)
                                                .setPositiveButton(R.string.save_name, new DialogInterface.OnClickListener() {
                                                    public void onClick(DialogInterface dialog, int whichButton) {
                                                        String label = address_label.getText().toString();
                                                        if (label != null && label.trim().length() > 0) {
                                                            legacyAddress.setLabel(label);
                                                        } else {
                                                            legacyAddress.setLabel(legacyAddress.getAddress());
                                                        }

                                                        remoteSaveNewAddress(legacyAddress);

                                                    }
                                                }).setNegativeButton(R.string.polite_no, new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface dialog, int whichButton) {
                                                legacyAddress.setLabel(legacyAddress.getAddress());
                                                remoteSaveNewAddress(legacyAddress);

                                            }
                                        }).show();

                                    } else {
                                        ToastCustom.makeText(getApplicationContext(), getString(R.string.bip38_error), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                                    }
                                } catch (Exception e) {
                                    ToastCustom.makeText(AccountActivity.this, getString(R.string.bip38_error), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                                } finally {
                                    if (progress != null && progress.isShowing()) {
                                        progress.dismiss();
                                        progress = null;
                                    }
                                }

                                Looper.loop();

                            }
                        }).start();

                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void importNonBIP38Address(final String format, final String data) {

        ECKey key = null;

        try {
            key = PrivateKeyFactory.getInstance().getKey(format, data);
        } catch (Exception e) {
            ToastCustom.makeText(AccountActivity.this, getString(R.string.no_private_key), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
            e.printStackTrace();
            return;
        }

        if (key != null && key.hasPrivKey() && payloadManager.getPayload().getLegacyAddressStrings().contains(key.toAddress(MainNetParams.get()).toString())) {

            //A private key to an existing address has been scanned
            setPrivateKey(key);

        } else if (key != null && key.hasPrivKey() && !payloadManager.getPayload().getLegacyAddressStrings().contains(key.toAddress(MainNetParams.get()).toString())) {

            final List<LegacyAddress> rollbackLegacyAddresses = payloadManager.getPayload().getLegacyAddresses();

            final LegacyAddress legacyAddress = new LegacyAddress(null, System.currentTimeMillis() / 1000L, key.toAddress(MainNetParams.get()).toString(), "", 0L, "android", BuildConfig.VERSION_NAME);
            /*
             * if double encrypted, save encrypted in payload
             */
            if (!payloadManager.getPayload().isDoubleEncrypted()) {
                legacyAddress.setEncryptedKey(key.getPrivKeyBytes());
            } else {
                String encryptedKey = Base58.encode(key.getPrivKeyBytes());
                String encrypted2 = DoubleEncryptionFactory.getInstance().encrypt(encryptedKey,
                        payloadManager.getPayload().getSharedKey(),
                        secondPassword,
                        payloadManager.getPayload().getOptions().getIterations());
                legacyAddress.setEncryptedKey(encrypted2);
            }

            final AppCompatEditText address_label = new AppCompatEditText(AccountActivity.this);
            address_label.setFilters(new InputFilter[]{new InputFilter.LengthFilter(ADDRESS_LABEL_MAX_LENGTH)});
            address_label.setInputType(InputType.TYPE_TEXT_FLAG_CAP_WORDS);

            FrameLayout frameLayout = new FrameLayout(AccountActivity.this);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int marginInPixels = (int) ViewUtils.convertDpToPixel(20, AccountActivity.this);
            params.setMargins(marginInPixels, 0, marginInPixels, 0);
            frameLayout.addView(address_label, params);

            final ECKey scannedKey = key;

            new Thread(new Runnable() {
                @Override
                public void run() {
                    Looper.prepare();

                    new AlertDialog.Builder(AccountActivity.this, R.style.AlertDialogStyle)
                            .setTitle(R.string.app_name)
                            .setMessage(R.string.label_address)
                            .setCancelable(false)
                            .setView(frameLayout)
                            .setPositiveButton(R.string.save_name, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int whichButton) {

                                    String label = address_label.getText().toString();
                                    if (label != null && label.trim().length() > 0) {
                                        legacyAddress.setLabel(label);
                                    } else {
                                        legacyAddress.setLabel(legacyAddress.getAddress());
                                    }

                                    remoteSaveNewAddress(legacyAddress);

                                }
                            }).setNegativeButton(R.string.polite_no, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {

                            legacyAddress.setLabel(legacyAddress.getAddress());
                            remoteSaveNewAddress(legacyAddress);

                        }
                    }).show();

                    Looper.loop();
                }
            }).start();

        } else {
            ToastCustom.makeText(AccountActivity.this, getString(R.string.no_private_key), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
        }

    }

    private void setPrivateKey(ECKey key){

        Payload payload = payloadManager.getPayload();
        int index = payload.getLegacyAddressStrings().indexOf(key.toAddress(MainNetParams.get()).toString());
        LegacyAddress legacyAddress = payload.getLegacyAddresses().get(index);
        if (!payload.isDoubleEncrypted()) {
            legacyAddress.setEncryptedKey(key.getPrivKeyBytes());
        } else {
            String encryptedKey = Base58.encode(key.getPrivKeyBytes());
            String encrypted2 = DoubleEncryptionFactory.getInstance().encrypt(encryptedKey,
                    payload.getSharedKey(),
                    secondPassword,
                    payload.getOptions().getIterations());
            legacyAddress.setEncryptedKey(encrypted2);
        }
        legacyAddress.setWatchOnly(false);
        payloadManager.setPayload(payload);
        PayloadBridge.getInstance().remoteSaveThread(new PayloadBridge.PayloadSaveListener() {
            @Override
            public void onSaveSuccess() {
                ToastCustom.makeText(AccountActivity.this, AccountActivity.this.getString(R.string.private_key_successfully_imported), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_OK);
                onUpdateAccountsList();
            }

            @Override
            public void onSaveFail() {
                ToastCustom.makeText(AccountActivity.this, AccountActivity.this.getString(R.string.remote_save_ko), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                //TODO revert on fail
            }
        });
    }

    private void importWatchOnly(String address){

        // check for poorly formed BIP21 URIs
        if (address.startsWith("bitcoin://") && address.length() > 10) {
            address = "bitcoin:" + address.substring(10);
        }

        if (FormatsUtil.getInstance().isBitcoinUri(address)) {
            address = FormatsUtil.getInstance().getBitcoinAddress(address);
        }

        if(!FormatsUtil.getInstance().isValidBitcoinAddress(address)){
            ToastCustom.makeText(AccountActivity.this, getString(R.string.invalid_bitcoin_address), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
        }else if (payloadManager.getPayload().getLegacyAddressStrings().contains(address)) {
            ToastCustom.makeText(AccountActivity.this, getString(R.string.address_already_in_wallet), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
        } else {

            final String finalAddress = address;
            new AlertDialog.Builder(this, R.style.AlertDialogStyle)
                    .setTitle(R.string.warning)
                    .setCancelable(false)
                    .setMessage(getString(R.string.watch_only_import_warning))
                    .setPositiveButton(R.string.dialog_continue, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {

                            final LegacyAddress legacyAddress = new LegacyAddress();
                            legacyAddress.setAddress(finalAddress);
                            legacyAddress.setCreatedDeviceName("android");
                            legacyAddress.setCreated(System.currentTimeMillis());
                            legacyAddress.setCreatedDeviceVersion(BuildConfig.VERSION_NAME);
                            legacyAddress.setWatchOnly(true);

                            final AppCompatEditText address_label = new AppCompatEditText(AccountActivity.this);
                            address_label.setFilters(new InputFilter[]{new InputFilter.LengthFilter(ADDRESS_LABEL_MAX_LENGTH)});
                            address_label.setInputType(InputType.TYPE_TEXT_FLAG_CAP_WORDS);

                            FrameLayout frameLayout = new FrameLayout(AccountActivity.this);
                            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                            int marginInPixels = (int) ViewUtils.convertDpToPixel(20, AccountActivity.this);
                            params.setMargins(marginInPixels, 0, marginInPixels, 0);
                            frameLayout.addView(address_label, params);

                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    Looper.prepare();

                                    new AlertDialog.Builder(AccountActivity.this, R.style.AlertDialogStyle)
                                            .setTitle(R.string.app_name)
                                            .setMessage(R.string.label_address)
                                            .setView(frameLayout)
                                            .setCancelable(false)
                                            .setPositiveButton(R.string.save_name, new DialogInterface.OnClickListener() {
                                                public void onClick(DialogInterface dialog, int whichButton) {

                                                    String label = address_label.getText().toString();
                                                    if (label != null && label.trim().length() > 0) {
                                                        legacyAddress.setLabel(label);
                                                    } else {
                                                        legacyAddress.setLabel(legacyAddress.getAddress());
                                                    }

                                                    remoteSaveNewAddress(legacyAddress);

                                                }
                                            }).setNegativeButton(R.string.polite_no, new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface dialog, int whichButton) {

                                            legacyAddress.setLabel(legacyAddress.getAddress());
                                            remoteSaveNewAddress(legacyAddress);

                                        }
                                    }).show();

                                    Looper.loop();
                                }
                            }).start();
                        }
                    }).setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                }
            }).show();
        }
    }

    private void addAddressAndUpdateList(final LegacyAddress legacyAddress) {

        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                JSONObject info = new AddressInfo().getAddressInfo(legacyAddress.getAddress(), "&limit=0");//limit 0 tx, since we only want final balance

                long balance = 0l;
                if (info != null)
                    try {
                        balance = info.getLong("final_balance");
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                MultiAddrFactory.getInstance().setLegacyBalance(legacyAddress.getAddress(), balance);
                MultiAddrFactory.getInstance().setLegacyBalance(MultiAddrFactory.getInstance().getLegacyBalance() + balance);

                onUpdateAccountsList();

                Looper.loop();

            }
        }).start();
    }

    private void addAddress() {

        final Handler mHandler = new Handler();

        final MaterialProgressDialog progress = new MaterialProgressDialog(this);
        progress.setMessage(getString(R.string.please_wait));
        progress.setCancelable(false);

        new AsyncTask<Void, Void, LegacyAddress>() {

            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                progress.show();
            }

            @Override
            protected LegacyAddress doInBackground(Void... params) {

                new AppUtil(context).applyPRNGFixes();
                return payloadManager.generateLegacyAddress("android", BuildConfig.VERSION_NAME, secondPassword);
            }

            @Override
            protected void onPostExecute(LegacyAddress legacyAddress) {
                super.onPostExecute(legacyAddress);

                if(legacyAddress != null){
                    new Thread(() -> {
                        try {
                            mHandler.post(() -> {
                                final AppCompatEditText address_label = new AppCompatEditText(AccountActivity.this);
                                address_label.setFilters(new InputFilter[]{new InputFilter.LengthFilter(ADDRESS_LABEL_MAX_LENGTH)});
                                address_label.setInputType(InputType.TYPE_TEXT_FLAG_CAP_WORDS);

                                FrameLayout frameLayout = new FrameLayout(AccountActivity.this);
                                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                                int marginInPixels = (int) ViewUtils.convertDpToPixel(20, AccountActivity.this);
                                params.setMargins(marginInPixels, 0, marginInPixels, 0);
                                frameLayout.addView(address_label, params);

                                new AlertDialog.Builder(AccountActivity.this, R.style.AlertDialogStyle)
                                        .setTitle(R.string.app_name)
                                        .setMessage(R.string.label_address2)
                                        .setView(frameLayout)
                                        .setCancelable(false)
                                        .setPositiveButton(R.string.save_name, (dialog, whichButton) -> {
                                            String label = address_label.getText().toString();
                                            if (label != null && label.trim().length() > 0) {
                                                ;
                                            } else {
                                                label = legacyAddress.getAddress();
                                            }

                                            legacyAddress.setLabel(label);
                                            remoteSaveNewAddress(legacyAddress);

                                        }).setNegativeButton(R.string.polite_no, (dialog, whichButton) -> {

                                    legacyAddress.setLabel(legacyAddress.getAddress());
                                    remoteSaveNewAddress(legacyAddress);

                                }).show();
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();
                }else{
                    ToastCustom.makeText(context, context.getString(R.string.cannot_create_address), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                }

                progress.dismiss();
            }
        }.execute();
    }

    private void remoteSaveNewAddress(final LegacyAddress legacy) {

        if (!ConnectivityStatus.hasConnectivity(AccountActivity.this)) {
            ToastCustom.makeText(AccountActivity.this, getString(R.string.check_connectivity_exit), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
            return;
        }

        final MaterialProgressDialog progress = new MaterialProgressDialog(this);
        progress.setMessage(getString(R.string.saving_address));
        progress.setCancelable(false);
        progress.show();

        new AsyncTask<Void, Void, Void>() {

            @Override
            protected Void doInBackground(Void... params) {

                if(payloadManager.addLegacyAddress(legacy)){
                    ToastCustom.makeText(AccountActivity.this, AccountActivity.this.getString(R.string.remote_save_ok), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_OK);
                    ToastCustom.makeText(getApplicationContext(), legacy.getAddress(), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_GENERAL);

                    List<String> legacyAddressList = payloadManager.getPayload().getLegacyAddressStrings();
                    try {
                        MultiAddrFactory.getInstance().refreshLegacyAddressData(legacyAddressList.toArray(new String[legacyAddressList.size()]), false);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    //Subscribe to new address only if successfully created
                    Intent intent = new Intent(WebSocketService.ACTION_INTENT);
                    intent.putExtra("address", legacy.getAddress());
                    LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

                    addAddressAndUpdateList(legacy);

                }else{
                    ToastCustom.makeText(AccountActivity.this, AccountActivity.this.getString(R.string.remote_save_ko), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                    appUtil.restartApp();
                }

                progress.dismiss();

                return null;
            }
        }.execute();
    }

    @Override
    public void onShowTransferableLegacyFundsWarning(boolean isAutoPopup, ArrayList<PendingTransaction> pendingTransactionList, long totalBalance, long totalFee) {

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this, R.style.AlertDialogStyle);
        AlertPromptTransferFundsBinding dialogBinding = DataBindingUtil.inflate(LayoutInflater.from(this),
                R.layout.alert_prompt_transfer_funds, null, false);
        dialogBuilder.setView(dialogBinding.getRoot());

        final AlertDialog alertDialog = dialogBuilder.create();
        alertDialog.setCanceledOnTouchOutside(false);

        if(!isAutoPopup){
            dialogBinding.confirmDontAskAgain.setVisibility(View.GONE);
        }

        dialogBinding.confirmCancel.setOnClickListener(v -> {
            if(dialogBinding.confirmDontAskAgain.isChecked()) prefsUtil.setValue("WARN_TRANSFER_ALL", false);
            alertDialog.dismiss();
        });

        dialogBinding.confirmSend.setOnClickListener(v -> {
            if(dialogBinding.confirmDontAskAgain.isChecked()) prefsUtil.setValue("WARN_TRANSFER_ALL", false);
            transferSpendableFunds(pendingTransactionList, totalBalance, totalFee);
            alertDialog.dismiss();
        });

        alertDialog.show();

        // This corrects the layout size after view drawn
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(alertDialog.getWindow().getAttributes());
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        alertDialog.getWindow().setAttributes(lp);
    }

    private void transferSpendableFunds(ArrayList<PendingTransaction> pendingTransactionList, long totalBalance, long totalFee) {

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);
        FragmentSendConfirmBinding dialogBinding = DataBindingUtil.inflate(LayoutInflater.from(this),
                R.layout.fragment_send_confirm, null, false);
        dialogBuilder.setView(dialogBinding.getRoot());

        final AlertDialog alertDialog = dialogBuilder.create();
        alertDialog.setCanceledOnTouchOutside(false);

        String fiatUnit = prefsUtil.getValue(PrefsUtil.KEY_SELECTED_FIAT, PrefsUtil.DEFAULT_CURRENCY);
        String btcUnit =  monetaryUtil.getBTCUnit(prefsUtil.getValue(PrefsUtil.KEY_BTC_UNITS, MonetaryUtil.UNIT_BTC));
        double exchangeRate = ExchangeRateFactory.getInstance().getLastPrice(fiatUnit);

        String fiatAmount = monetaryUtil.getFiatFormat(fiatUnit).format(exchangeRate * ((double) totalBalance / 1e8));
        String fiatFee = monetaryUtil.getFiatFormat(fiatUnit).format(exchangeRate * ((double) totalFee / 1e8));
        String fiatTotal = monetaryUtil.getFiatFormat(fiatUnit).format(exchangeRate * ((double) (totalBalance+totalFee) / 1e8));

        dialogBinding.confirmFromLabel.setText(pendingTransactionList.size()+" "+getResources().getString(R.string.spendable_addresses));
        int defaultIndex = payloadManager.getPayload().getHdWallet().getDefaultIndex();
        Account defaultAccount = payloadManager.getPayload().getHdWallet().getAccounts().get(defaultIndex);
        dialogBinding.confirmToLabel.setText(defaultAccount.getLabel()+" ("+getResources().getString(R.string.default_label)+")");
        dialogBinding.confirmAmountBtcUnit.setText(btcUnit);
        dialogBinding.confirmAmountFiatUnit.setText(fiatUnit);
        dialogBinding.confirmAmountBtc.setText(monetaryUtil.getDisplayAmount(totalBalance));
        dialogBinding.confirmAmountFiat.setText(fiatAmount);
        dialogBinding.confirmFeeBtc.setText(monetaryUtil.getDisplayAmount(totalFee));
        dialogBinding.confirmFeeFiat.setText(fiatFee);
        dialogBinding.confirmTotalBtc.setText(monetaryUtil.getDisplayAmount(totalBalance + totalFee));
        dialogBinding.confirmTotalFiat.setText(fiatTotal);

        dialogBinding.tvCustomizeFee.setVisibility(View.GONE);

        dialogBinding.confirmCancel.setOnClickListener(v -> {
            if (alertDialog.isShowing()) {
                alertDialog.cancel();
            }
        });

        dialogBinding.confirmSend.setOnClickListener(v -> {
            new SecondPasswordHandler(AccountActivity.this).validate(new SecondPasswordHandler.ResultListener() {
                @Override
                public void onNoSecondPassword() {
                    viewModel.sendPayment(pendingTransactionList, null);
                }

                @Override
                public void onSecondPasswordValidated(String validateSecondPassword) {
                    secondPassword = validateSecondPassword;
                    viewModel.sendPayment(pendingTransactionList, secondPassword);
                }
            });

            alertDialog.dismiss();
        });

        alertDialog.show();
    }

    @Override
    public void onSetTransferLegacyFundsMenuItemVisible(boolean visible) {
        runOnUiThread(() -> transferFundsMenuItem.setVisible(visible));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PermissionUtil.PERMISSION_REQUEST_CAMERA) {
            if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScanActivity();
            } else {
                // Permission request was denied.
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onShowTransactionSuccess(ArrayList<PendingTransaction> pendingSpendList) {
        runOnUiThread(() -> {
            AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);
            LayoutInflater inflater = getLayoutInflater();
            View dialogView = inflater.inflate(R.layout.modal_transaction_success, null);
            transactionSuccessDialog  = dialogBuilder.setView(dialogView).create();
            transactionSuccessDialog = dialogBuilder.setView(dialogView)
                    .setPositiveButton(getString(R.string.done), null)
                    .create();
            transactionSuccessDialog.setTitle(R.string.transaction_submitted);
            transactionSuccessDialog.setOnDismissListener(dialog -> {
                if (pendingSpendList != null && !pendingSpendList.isEmpty()) {
                    onShowArchiveDialog(pendingSpendList);
                }
            });
            transactionSuccessDialog.show();

            dialogHandler.postDelayed(dialogRunnable, 5 * 1000);
        });
    }

    private final Handler dialogHandler = new Handler();
    private final Runnable dialogRunnable = () -> {
        if (transactionSuccessDialog != null && transactionSuccessDialog.isShowing()) {
            transactionSuccessDialog.dismiss();
        }
    };

    @Override
    public void onShowProgressDialog(String title, String message) {
        onDismissProgressDialog();

        progress = new MaterialProgressDialog(this);
        progress.setMessage(message);
        progress.show();
    }

    @Override
    public void onDismissProgressDialog() {
        if (progress != null && progress.isShowing()) {
            progress.dismiss();
            progress = null;
        }
    }

    public void onShowArchiveDialog(ArrayList<PendingTransaction> pendingSpendList) {
        int numberOfAddresses = pendingSpendList.size();
        new AlertDialog.Builder(this, R.style.AlertDialogStyle)
                .setTitle(R.string.transfer_success_archive_prompt_title)
                .setMessage(getResources().getQuantityString(R.plurals.transfer_success_archive_prompt_plurals, numberOfAddresses, numberOfAddresses))
                .setPositiveButton(R.string.archive, (dialogInterface, i) -> {
                    for (PendingTransaction spend : pendingSpendList) {
                        ((LegacyAddress) spend.sendingObject.accountObject).setTag(PayloadManager.ARCHIVED_ADDRESS);
                    }

                    new ArchiveAsync(this, payloadManager).execute();
                })
                .setNegativeButton(android.R.string.no, null)
                .setOnDismissListener(dialog -> onResume())
                .show();
    }

    private static class ArchiveAsync extends AsyncTask<Void, Void, Void> {

        private MaterialProgressDialog progress;
        private final WeakReference<AccountActivity> context;
        private PayloadManager payloadManager;

        ArchiveAsync(AccountActivity activity, PayloadManager manager) {
            super();
            context = new WeakReference<>(activity);
            payloadManager = manager;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            AccountActivity accountActivity = context.get();
            if (accountActivity != null) {
                progress = new MaterialProgressDialog(accountActivity);
                progress.setMessage(accountActivity.getResources().getString(R.string.please_wait));
                progress.setCancelable(false);
                progress.show();
            }
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            if (progress != null && progress.isShowing()) {
                progress.dismiss();
                progress = null;
            }
            AccountActivity accountActivity = context.get();
            if (accountActivity != null) {
                accountActivity.onUpdateAccountsList();
            }
        }

        @Override
        protected Void doInBackground(Void... voids) {
            if (payloadManager.savePayloadToServer()) {
                try {
                    payloadManager.updateBalancesAndTransactions();
                } catch (Exception e) {
                    Log.e(ArchiveAsync.class.getSimpleName(), "doInBackground: ", e);
                }
            }
            return null;
        }
    }
}
