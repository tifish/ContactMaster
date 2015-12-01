package com.tinyfish.contactmaster;

import android.app.Activity;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class MainActivity extends Activity {

    protected TextView logTextView;
    String TAG = "ContactMaster";

    protected void log(String line) {
        Log.i(TAG, line);

        logTextView.append(line);
        logTextView.append("\n");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        logTextView = (TextView) findViewById(R.id.logTextView);
    }

    public void onButtonClick(View v) {
        log("Begin.");

        Uri uri = Uri.parse("content://com.android.contacts/contacts");
        //获得一个ContentResolver数据共享的对象
        ContentResolver reslover = getContentResolver();
        //取得联系人中开始的游标，通过content://com.android.contacts/contacts这个路径获得
        Cursor cursor = reslover.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);

        try {
            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

            while (cursor.moveToNext()) {
                String rawContactID = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.NAME_RAW_CONTACT_ID));
                String contactName = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
                String logTitle = String.format("[%s]%s", new Object[]{rawContactID, contactName});

                // 删除名字为空的联系人
                if (contactName == null || contactName.trim().isEmpty()) {
                    ops.add(ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
                            .withSelection(ContactsContract.RawContacts._ID + "=?", new String[]{rawContactID})
                            .build());
                } else {
                    boolean hasPhoneNumber = processPhoneNumbers(reslover, rawContactID, logTitle, ops);
                    boolean hasEmail = processEmails(reslover, rawContactID, logTitle, ops);
                    boolean hasAddress = processAddresses(reslover, rawContactID, logTitle, ops);

                    // 删除没有电话、email、地址的联系人
                    if (!hasPhoneNumber && !hasEmail && !hasAddress) {
                        ops.add(ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
                                .withSelection(ContactsContract.RawContacts._ID + "=?", new String[]{rawContactID})
                                .build());
                    }
                }
            }

            applyBatchOps(ops);
        }
        finally {
            cursor.close();
        }

        log("End.");
    }

    private boolean processPhoneNumbers(ContentResolver reslover, String rawContactID, String logTitle, ArrayList<ContentProviderOperation> ops) {
        Cursor phoneCursor = reslover.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Phone._ID,
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.Data.RAW_CONTACT_ID + "=" + rawContactID, null, null);

        try {
            if (phoneCursor.getCount() == 0) {
                return false;
            }

            HashMap<String, Integer> phones = new HashMap<>();
            int phoneNumberCount = 0;
            ArrayList<ContentProviderOperation> trimOps = new ArrayList<ContentProviderOperation>();

            while (phoneCursor.moveToNext()) { // 取得电话号码(可能存在多个号码)
                String phoneNumber = phoneCursor.getString(2);
                String unifiedPhoneNumber = phoneNumber.trim().replaceAll("-", "");
                phones.put(unifiedPhoneNumber, phoneCursor.getInt(1));
                phoneNumberCount++;

                if (!phoneNumber.equals(unifiedPhoneNumber)) {
                    log(logTitle + "：电话号码不规范，自动修正：" + phoneNumber);
                    String id = phoneCursor.getString(0);
                    trimOps.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection(ContactsContract.CommonDataKinds.Phone._ID + "=?", new String[]{id})
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, unifiedPhoneNumber)
                            .build());
                }
            }

            if (phoneNumberCount != phones.size()) {
                log(logTitle + "：电话号码冗余，自动合并。");

                // 删除原有号码
                ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection(ContactsContract.Data.RAW_CONTACT_ID + "=? and " + ContactsContract.Data.MIMETYPE + "=?", new String[]{rawContactID, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE})
                        .build());

                // 重新添加号码
                for (Map.Entry<String, Integer> entry : phones.entrySet()) {
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID, rawContactID)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, entry.getValue())
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, entry.getKey())
                            .build());
                }
            } else {
                ops.addAll(trimOps);
            }
        }
        finally {
            phoneCursor.close();
        }

        return true;
    }

    private boolean processEmails(ContentResolver reslover, String rawContactID, String logTitle, ArrayList<ContentProviderOperation> ops) {
        Cursor emailCursor = reslover.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Email._ID,
                        ContactsContract.CommonDataKinds.Email.TYPE,
                        ContactsContract.CommonDataKinds.Email.ADDRESS},
                ContactsContract.Data.RAW_CONTACT_ID + "=" + rawContactID, null, null);

        try {
            if (emailCursor.getCount() == 0) {
                return false;
            }

            HashMap<String, Integer> emails = new HashMap<>();
            int emailCount = 0;
            ArrayList<ContentProviderOperation> trimOps = new ArrayList<ContentProviderOperation>();

            while (emailCursor.moveToNext()) { // 取得电话号码(可能存在多个号码)
                String email = emailCursor.getString(2);
                String unifiedEmail = email.trim();
                emails.put(unifiedEmail, emailCursor.getInt(1));
                emailCount++;

                if (!email.equals(unifiedEmail)) {
                    log(logTitle + "：邮箱不规范，自动修正：" + email);
                    String id = emailCursor.getString(0);
                    trimOps.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection(ContactsContract.CommonDataKinds.Email._ID + "=?", new String[]{id})
                            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, unifiedEmail)
                            .build());
                }
            }

            if (emailCount != emails.size()) {
                log(logTitle + "：邮箱冗余，自动合并。");

                // 删除原有邮箱
                ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection(ContactsContract.Data.RAW_CONTACT_ID + "=? and " + ContactsContract.Data.MIMETYPE + "=?",
                                new String[]{rawContactID, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE})
                        .build());

                // 重新添加邮箱
                for (Map.Entry<String, Integer> entry : emails.entrySet()) {
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.CommonDataKinds.Email.RAW_CONTACT_ID, rawContactID)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Email.TYPE, entry.getValue())
                            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, entry.getKey())
                            .build());
                }
            } else {
                ops.addAll(trimOps);
            }
        }
        finally {
            emailCursor.close();
        }

        return true;
    }

    private boolean processAddresses(ContentResolver reslover, String rawContactID, String logTitle, ArrayList<ContentProviderOperation> ops) {
        String[] uris = new String[]{
                ContactsContract.CommonDataKinds.StructuredPostal._ID,
                ContactsContract.CommonDataKinds.StructuredPostal.TYPE,
                ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                ContactsContract.CommonDataKinds.StructuredPostal.CITY,
                ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY,
                ContactsContract.CommonDataKinds.StructuredPostal.NEIGHBORHOOD,
                ContactsContract.CommonDataKinds.StructuredPostal.POBOX,
                ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
                ContactsContract.CommonDataKinds.StructuredPostal.STREET,
        };
        Cursor addrCursor = reslover.query(
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                uris,
                ContactsContract.CommonDataKinds.StructuredPostal.RAW_CONTACT_ID + "=" + rawContactID, null, null);

        try {
            if (addrCursor.getCount() == 0) {
                return false;
            }

            HashMap<String, Integer> addresses = new HashMap<>();
            int addrCount = 0;
            ArrayList<ContentProviderOperation> trimOps = new ArrayList<ContentProviderOperation>();

            // 取得地址
            while (addrCursor.moveToNext()) {
                addresses.put(addrCursor.getString(2).trim(), addrCursor.getInt(1));
                addrCount++;

                // 修正不规范的地址信息
                ContentProviderOperation.Builder trimOpBuilder = null;

                for (int i = 3; i < uris.length; i++) {
                    String originalContent = addrCursor.getString(i);
                    if (originalContent == null) {
                        continue;
                    }
                    String unifiedContent = originalContent.trim();

                    if (!originalContent.equals(unifiedContent)) {
                        log(logTitle + "：地址信息不规范，自动修正：" + originalContent);
                        if (trimOpBuilder == null) {
                            String id = addrCursor.getString(0);
                            trimOpBuilder = ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                                    .withSelection(ContactsContract.CommonDataKinds.StructuredPostal._ID + "=?", new String[]{id});
                        }
                        trimOpBuilder.withValue(uris[i], unifiedContent);
                    }
                }

                if (trimOpBuilder != null) {
                    trimOps.add(trimOpBuilder.build());
                    trimOpBuilder = null;
                }
            }

            addrCursor.close();

            if (!trimOps.isEmpty()) {
                // 修正地址信息后，再次查找冗余地址
                applyBatchOps(trimOps);

                addrCursor = reslover.query(
                        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                        uris,
                        ContactsContract.CommonDataKinds.StructuredPostal.RAW_CONTACT_ID + "=" + rawContactID, null, null);
                addresses.clear();

                while (addrCursor.moveToNext()) {
                    addresses.put(addrCursor.getString(2).trim(), addrCursor.getInt(1));
                }
            }

            // 合并冗余地址
            if (addrCount != addresses.size()) {
                log(logTitle + "：地址冗余，自动合并。");

                // 删除原有地址
                ops.add(ContentProviderOperation
                        .newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection(ContactsContract.Data.RAW_CONTACT_ID + "=? and " + ContactsContract.Data.MIMETYPE + "=?", new String[]{rawContactID, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE})
                        .build());

                // 重新添加地址
                for (Map.Entry<String, Integer> entry : addresses.entrySet()) {
                    ops.add(ContentProviderOperation
                            .newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactID)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, entry.getValue())
                            .withValue(ContactsContract.CommonDataKinds.StructuredPostal.STREET, entry.getKey())
                            .build());
                }
            }
        }
        finally {
            addrCursor.close();
        }

        return true;
    }

    void applyBatchOps(ArrayList<ContentProviderOperation> ops) {
        if (!ops.isEmpty()) {
            try {
                getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            } catch (RemoteException e) {
                e.printStackTrace();
            } catch (OperationApplicationException e) {
                e.printStackTrace();
            }

            ops.clear();
        }
    }
}
