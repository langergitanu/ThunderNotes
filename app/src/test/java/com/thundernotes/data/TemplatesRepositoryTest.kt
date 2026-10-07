package com.thundernotes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thundernotes.data.db.AppDatabase
import com.thundernotes.data.repository.TemplatesRepository
import com.thundernotes.data.seed.TemplatesSeed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifies [TemplatesRepository.seedPreinstalledTemplates] actually inserts
 * the 60 preinstalled covers + is idempotent (spec §6.8). Mirrors the
 * [AppDatabaseTest] in-memory-DB pattern.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TemplatesRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TemplatesRepository

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = TemplatesRepository(db.templateDao())
    }

    @After fun teardown() { db.close() }

    @Test fun `seed inserts all 60 preinstalled templates`() = runBlocking {
        assertEquals(0, repo.count())
        repo.seedPreinstalledTemplates()
        assertEquals(TemplatesSeed.count, repo.count())
        assertTrue("expected ≥ 50 seeded, got ${repo.count()}", repo.count() >= 50)
    }

    @Test fun `seed is idempotent — second call is a no-op`() = runBlocking {
        repo.seedPreinstalledTemplates()
        val first = repo.count()
        repo.seedPreinstalledTemplates()
        assertEquals(first, repo.count())
    }

    @Test fun `seeded templates are queryable by category`() = runBlocking {
        repo.seedPreinstalledTemplates()
        val math = repo.observeByCategory("Mathematics").first()
        assertTrue("expected Math covers, got ${math.size}", math.isNotEmpty())
    }
}
