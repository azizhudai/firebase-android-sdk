// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore.testutil;

import static com.google.firebase.firestore.testutil.TestUtil.map;
import static com.google.firebase.firestore.util.Util.autoId;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.fail;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.AccessHelper;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.auth.EmptyCredentialsProvider;
import com.google.firebase.firestore.core.DatabaseInfo;
import com.google.firebase.firestore.local.Persistence;
import com.google.firebase.firestore.local.SQLitePersistence;
import com.google.firebase.firestore.model.DatabaseId;
import com.google.firebase.firestore.testutil.provider.FirestoreProvider;
import com.google.firebase.firestore.util.AsyncQueue;
import com.google.firebase.firestore.util.Logger;
import com.google.firebase.firestore.util.Logger.Level;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A set of helper methods for tests */
public class IntegrationTestUtil {

  // Alternate project ID for creating "bad" references. Doesn't actually need to work.
  public static final String BAD_PROJECT_ID = "test-project-2";

  /** Online status of all active Firestore clients. */
  private static final Map<FirebaseFirestore, Boolean> firestoreStatus = new HashMap<>();

  private static final long SEMAPHORE_WAIT_TIMEOUT_MS = 30000;
  private static final long SHUTDOWN_WAIT_TIMEOUT_MS = 10000;
  private static final long BATCH_WAIT_TIMEOUT_MS = 120000;

  private static final FirestoreProvider provider = new FirestoreProvider();

  /**
   * TODO: There's some flakiness with hexa / emulator / whatever that causes the first write in a
   * run to frequently time out. So for now we always send an initial write with an extra long
   * timeout to improve test reliability.
   */
  private static final long FIRST_WRITE_TIMEOUT_MS = 60000;

  private static boolean sentFirstWrite = false;

  public static FirestoreProvider provider() {
    return provider;
  }

  public static DatabaseInfo testEnvDatabaseInfo() {
    return new DatabaseInfo(
        DatabaseId.forProject(provider.projectId()),
        "test-persistenceKey",
        provider.firestoreHost(),
        /*sslEnabled=*/ true);
  }

  public static FirebaseFirestoreSettings newTestSettings() {
    return newTestSettingsWithSnapshotTimestampsEnabled(true);
  }

  public static FirebaseFirestoreSettings newTestSettingsWithSnapshotTimestampsEnabled(
      boolean enabled) {
    return new FirebaseFirestoreSettings.Builder()
        .setHost(provider.firestoreHost())
        .setPersistenceEnabled(true)
        .setTimestampsInSnapshotsEnabled(enabled)
        .build();
  }

  /** Initializes a new Firestore instance that uses the default project. */
  public static FirebaseFirestore testFirestore() {
    return testFirestore(newTestSettings());
  }

  /**
   * Initializes a new Firestore instance that uses the default project, customized with the
   * provided settings.
   */
  public static FirebaseFirestore testFirestore(FirebaseFirestoreSettings settings) {
    FirebaseFirestore firestore = testFirestore(provider.projectId(), Level.DEBUG, settings);
    if (!sentFirstWrite) {
      sentFirstWrite = true;
      waitFor(
          firestore.document("test-collection/initial-write-doc").set(map("foo", 1)),
          FIRST_WRITE_TIMEOUT_MS);
    }
    return firestore;
  }

  /** Initializes a new Firestore instance that uses a non-existing default project. */
  public static FirebaseFirestore testAlternateFirestore() {
    return testFirestore(BAD_PROJECT_ID, Level.DEBUG, newTestSettings());
  }

  private static void clearPersistence(
      Context context, DatabaseId databaseId, String persistenceKey) {
    @SuppressWarnings("VisibleForTests")
    String databaseName = SQLitePersistence.databaseName(persistenceKey, databaseId);
    String sqlLitePath = context.getDatabasePath(databaseName).getPath();
    String journalPath = sqlLitePath + "-journal";
    new File(sqlLitePath).delete();
    new File(journalPath).delete();
  }

  /**
   * Initializes a new Firestore instance that can be used in testing. It is guaranteed to not share
   * state with other instances returned from this call.
   */
  public static FirebaseFirestore testFirestore(
      String projectId, Logger.Level logLevel, FirebaseFirestoreSettings settings) {
    // This unfortunately is a global setting that affects existing Firestore clients.
    Logger.setLogLevel(logLevel);

    // TODO: Remove this once this is ready to ship.
    Persistence.INDEXING_SUPPORT_ENABLED = true;

    Context context = InstrumentationRegistry.getContext();
    DatabaseId databaseId = DatabaseId.forDatabase(projectId, DatabaseId.DEFAULT_DATABASE_ID);
    String persistenceKey = "db" + firestoreStatus.size();

    clearPersistence(context, databaseId, persistenceKey);

    AsyncQueue asyncQueue = null;

    try {
      asyncQueue = new AsyncQueue();
    } catch (Exception e) {
      fail("Failed to initialize AsyncQueue:" + e);
    }

    FirebaseFirestore firestore =
        AccessHelper.newFirebaseFirestore(
            context,
            databaseId,
            persistenceKey,
            new EmptyCredentialsProvider(),
            asyncQueue,
            /*firebaseApp=*/ null);
    firestore.setFirestoreSettings(settings);
    firestoreStatus.put(firestore, true);

    return firestore;
  }

  public static void tearDown() {
    try {
      for (FirebaseFirestore firestore : firestoreStatus.keySet()) {
        Task<Void> result = AccessHelper.shutdown(firestore);
        try {
          Tasks.await(result, SHUTDOWN_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException | InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    } finally {
      firestoreStatus.clear();
    }
  }

  public static DocumentReference testDocument() {
    return testCollection("test-collection").document();
  }

  public static DocumentReference testDocumentWithData(Map<String, Object> data) {
    DocumentReference docRef = testDocument();
    waitFor(docRef.set(data));
    return docRef;
  }

  public static CollectionReference testCollection() {
    return testFirestore().collection(autoId());
  }

  public static CollectionReference testCollection(String name) {
    return testFirestore().collection(name + "_" + autoId());
  }

  public static CollectionReference testCollectionWithDocs(Map<String, Map<String, Object>> docs) {
    CollectionReference collection = testCollection();
    CollectionReference writer = testFirestore().collection(collection.getId());
    writeAllDocs(writer, docs);
    return collection;
  }

  public static void writeAllDocs(
      CollectionReference collection, Map<String, Map<String, Object>> docs) {
    for (Map.Entry<String, Map<String, Object>> doc : docs.entrySet()) {
      waitFor(collection.document(doc.getKey()).set(doc.getValue()));
    }
  }

  public static void waitForOnlineSnapshot(DocumentReference doc) {
    TaskCompletionSource<Void> done = new TaskCompletionSource<>();
    ListenerRegistration registration =
        doc.addSnapshotListener(
            MetadataChanges.INCLUDE,
            (snapshot, error) -> {
              assertNull(error);
              if (!snapshot.getMetadata().isFromCache()) {
                done.setResult(null);
              }
            });
    waitFor(done.getTask());
    registration.remove();
  }

  public static void waitFor(Semaphore semaphore) {
    waitFor(semaphore, 1);
  }

  public static void waitFor(Semaphore semaphore, int count) {
    try {
      boolean acquired =
          semaphore.tryAcquire(count, SEMAPHORE_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      if (!acquired) {
        throw new TimeoutException("Failed to acquire semaphore within test timeout");
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void waitFor(CountDownLatch countDownLatch) {
    try {
      boolean acquired = countDownLatch.await(SEMAPHORE_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      if (!acquired) {
        throw new TimeoutException("Failed to acquire countdown latch within test timeout");
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void waitFor(List<Task<?>> task) {
    try {
      Tasks.await(Tasks.whenAll(task), BATCH_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public static <T> T waitFor(Task<T> task) {
    return waitFor(task, SEMAPHORE_WAIT_TIMEOUT_MS);
  }

  public static <T> T waitFor(Task<T> task, long timeoutMS) {
    try {
      return Tasks.await(task, timeoutMS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public static <T> Exception waitForException(Task<T> task) {
    try {
      Tasks.await(task, SEMAPHORE_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      throw new RuntimeException("Expected Exception but Task completed successfully.");
    } catch (ExecutionException e) {
      return (Exception) e.getCause();
    } catch (TimeoutException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public static List<Map<String, Object>> querySnapshotToValues(QuerySnapshot querySnapshot) {
    List<Map<String, Object>> res = new ArrayList<>();
    for (DocumentSnapshot doc : querySnapshot) {
      res.add(doc.getData());
    }
    return res;
  }

  public static List<String> querySnapshotToIds(QuerySnapshot querySnapshot) {
    List<String> res = new ArrayList<>();
    for (DocumentSnapshot doc : querySnapshot) {
      res.add(doc.getId());
    }
    return res;
  }

  public static void disableNetwork(FirebaseFirestore firestore) {
    if (firestoreStatus.get(firestore)) {
      waitFor(firestore.disableNetwork());
      firestoreStatus.put(firestore, false);
    }
  }

  public static void enableNetwork(FirebaseFirestore firestore) {
    if (!firestoreStatus.get(firestore)) {
      waitFor(firestore.enableNetwork());
      // Wait for the client to connect.
      waitFor(firestore.collection("unknown").document().delete());
      firestoreStatus.put(firestore, true);
    }
  }

  public static boolean isNetworkEnabled(FirebaseFirestore firestore) {
    return firestoreStatus.get(firestore);
  }

  public static Map<String, Object> toDataMap(QuerySnapshot qrySnap) {
    Map<String, Object> result = new HashMap<>();
    for (DocumentSnapshot docSnap : qrySnap.getDocuments()) {
      result.put(docSnap.getId(), docSnap.getData());
    }
    return result;
  }
}