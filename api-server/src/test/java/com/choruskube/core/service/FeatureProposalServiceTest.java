package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.SingleTenant;
import com.choruskube.core.dto.FeatureProposalRequest;
import com.choruskube.core.dto.FeatureProposalResponse;
import com.choruskube.core.dto.InternalUpdateFeatureProposalRequest;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.FeatureProposal;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.RepoGroupMember;
import com.choruskube.core.model.enums.FeatureProposalStatus;
import com.choruskube.core.repository.FeatureProposalRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.RepoGroupRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class FeatureProposalServiceTest extends BaseTest {

    @Autowired
    private FeatureProposalService service;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private FeatureProposalRepository featureProposalRepo;

    @Autowired
    private RepoGroupRepository repoGroupRepo;

    @Autowired
    private SoftwareProjectRepository softwareProjectRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @Test
    void create_withGitRepoTarget_returnsSoftwareProjectRef_withType_git_repo() {
        GitRepo r = makeRepo("https://github.com/test/one.git");

        FeatureProposalResponse created =
                service.create(new FeatureProposalRequest("Title", "Desc", null, r.getId()), null);

        assertThat(created.softwareProject()).isNotNull();
        assertThat(created.softwareProject().id()).isEqualTo(r.getId());
        assertThat(created.softwareProject().type()).isEqualTo("git_repo");
        assertThat(created.repos()).hasSize(1);
        assertThat(created.repos().get(0).id()).isEqualTo(r.getId());
    }

    @Test
    void create_withRepoGroupTarget_returnsSoftwareProjectRef_withType_repo_group_andResolvedRepos() {
        GitRepo r1 = makeRepo("https://github.com/test/group-a.git");
        GitRepo r2 = makeRepo("https://github.com/test/group-b.git");
        RepoGroup group = makeGroup("group-1", List.of(r1, r2));

        FeatureProposalResponse created =
                service.create(new FeatureProposalRequest("Title", "Desc", null, group.getId()), null);

        assertThat(created.softwareProject().id()).isEqualTo(group.getId());
        assertThat(created.softwareProject().type()).isEqualTo("repo_group");
        assertThat(created.repos()).extracting(rr -> rr.id()).containsExactly(r1.getId(), r2.getId());
    }

    @Test
    void create_withNullSoftwareProjectId_throwsBadRequest() {
        assertThatThrownBy(() -> service.create(new FeatureProposalRequest("Title", "Desc", null, null), null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void create_withUnknownSoftwareProjectId_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(new FeatureProposalRequest("Title", "Desc", null, unknown), null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(unknown.toString());
    }

    @Test
    void create_withSoftwareProjectFromOtherOrg_allowedUnderAlwaysAllow() {
        GitRepo foreign = new GitRepo();
        foreign.setUrl("https://github.com/other-org/repo.git");
        foreign.setName(RepoNameUtil.deriveOwnerRepoName("https://github.com/other-org/repo.git"));
        foreign = gitRepoRepo.save(foreign);
        final UUID foreignId = foreign.getId();

        FeatureProposalResponse created =
                service.create(new FeatureProposalRequest("Title", "Desc", null, foreignId), null);
        assertThat(created.softwareProject().id()).isEqualTo(foreignId);
    }

    @Test
    void toResponse_repos_nameDerivedFromUrl() {
        GitRepo r = makeRepo("https://github.com/acme/derived-name.git");

        FeatureProposalResponse created =
                service.create(new FeatureProposalRequest("Title", "Desc", null, r.getId()), null);

        assertThat(created.repos()).hasSize(1);
        assertThat(created.repos().get(0).name()).isEqualTo("derived-name");
        assertThat(created.repos().get(0).url()).isEqualTo("https://github.com/acme/derived-name.git");
    }

    @Test
    void listBySoftwareProjectId_returnsProposalsForGivenProject() {
        GitRepo r = makeRepo("https://github.com/test/list-by-id.git");
        FeatureProposalResponse created = service.create(new FeatureProposalRequest("T", "D", null, r.getId()), null);

        List<FeatureProposalResponse> result = service.listBySoftwareProjectId(r.getId());
        assertThat(result).extracting(FeatureProposalResponse::id).contains(created.id());
    }

    @Test
    void listBySoftwareProjectId_ordersMostRecentFirst() {
        GitRepo shared = makeRepo("https://github.com/test/list-order.git");

        FeatureProposalResponse older =
                service.create(new FeatureProposalRequest("Older", "D", null, shared.getId()), null);
        // Force a timestamp gap: createdAt is db-set so on some test containers consecutive
        // creates land at the same instant. Sleep briefly to spread the two creates.
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        FeatureProposalResponse newer =
                service.create(new FeatureProposalRequest("Newer", "D", null, shared.getId()), null);

        List<FeatureProposalResponse> result = service.listBySoftwareProjectId(shared.getId());
        int newerIdx = -1, olderIdx = -1;
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).id().equals(newer.id())) newerIdx = i;
            if (result.get(i).id().equals(older.id())) olderIdx = i;
        }
        assertThat(newerIdx).isGreaterThanOrEqualTo(0);
        assertThat(olderIdx).isGreaterThanOrEqualTo(0);
        assertThat(newerIdx).isLessThan(olderIdx);
    }

    @Test
    void update_replacesSoftwareProjectId() {
        GitRepo r1 = makeRepo("https://github.com/test/upd-one.git");
        GitRepo r2 = makeRepo("https://github.com/test/upd-two.git");

        FeatureProposalResponse created =
                service.create(new FeatureProposalRequest("Orig", "Desc", null, r1.getId()), null);

        FeatureProposalResponse updated =
                service.update(created.id(), new FeatureProposalRequest("Orig", "Desc", null, r2.getId()));

        assertThat(updated.softwareProject().id()).isEqualTo(r2.getId());
        assertThat(updated.repos()).hasSize(1);
        assertThat(updated.repos().get(0).id()).isEqualTo(r2.getId());
    }

    // ── updateInternal: PATCH preserve semantics ──────────────────────────────────

    @Test
    void updateInternal_withNullTitle_preservesExistingTitle() {
        GitRepo r = makeRepo("https://github.com/test/upd-preserve-title.git");
        FeatureProposalResponse created =
                service.create(new FeatureProposalRequest("Original Title", "Desc", "Motivation", r.getId()), null);

        FeatureProposalResponse updated = service.updateInternal(
                created.id(),
                r.getId(),
                UUID.randomUUID(),
                new InternalUpdateFeatureProposalRequest(null, "New Desc", null));

        assertThat(updated.title()).isEqualTo("Original Title");
        assertThat(updated.description()).isEqualTo("New Desc");
        assertThat(updated.motivation()).isEqualTo("Motivation");
    }

    @Test
    void updateInternal_withNullMotivation_preservesExistingMotivation() {
        GitRepo r = makeRepo("https://github.com/test/upd-preserve-motivation.git");
        FeatureProposalResponse created =
                service.create(new FeatureProposalRequest("T", "D", "Keep me", r.getId()), null);

        FeatureProposalResponse updated = service.updateInternal(
                created.id(),
                r.getId(),
                UUID.randomUUID(),
                new InternalUpdateFeatureProposalRequest("New Title", null, null));

        assertThat(updated.motivation()).isEqualTo("Keep me");
    }

    @Test
    void updateInternal_withEmptyStringMotivation_clearsMotivation() {
        GitRepo r = makeRepo("https://github.com/test/upd-clear-motivation.git");
        FeatureProposalResponse created =
                service.create(new FeatureProposalRequest("T", "D", "Clear me", r.getId()), null);

        FeatureProposalResponse updated = service.updateInternal(
                created.id(), r.getId(), UUID.randomUUID(), new InternalUpdateFeatureProposalRequest(null, null, ""));

        assertThat(updated.motivation()).isNull();
    }

    // ── updateInternal: validation guards ────────────────────────────────────────

    @Test
    void updateInternal_withBlankTitle_throwsBadRequest() {
        GitRepo r = makeRepo("https://github.com/test/upd-blank-title.git");
        FeatureProposalResponse created = service.create(new FeatureProposalRequest("T", "D", null, r.getId()), null);

        assertThatThrownBy(() -> service.updateInternal(
                        created.id(),
                        r.getId(),
                        UUID.randomUUID(),
                        new InternalUpdateFeatureProposalRequest("   ", null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("title");
    }

    @Test
    void updateInternal_withBlankDescription_throwsBadRequest() {
        GitRepo r = makeRepo("https://github.com/test/upd-blank-desc.git");
        FeatureProposalResponse created = service.create(new FeatureProposalRequest("T", "D", null, r.getId()), null);

        assertThatThrownBy(() -> service.updateInternal(
                        created.id(),
                        r.getId(),
                        UUID.randomUUID(),
                        new InternalUpdateFeatureProposalRequest(null, "   ", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("description");
    }

    @Test
    void updateInternal_withNonBacklogStatus_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/upd-non-backlog.git");
        FeatureProposalResponse created = service.create(new FeatureProposalRequest("T", "D", null, r.getId()), null);

        // Directly set status to in_progress via repository to simulate post-start state.
        FeatureProposal fp = featureProposalRepo.findById(created.id()).orElseThrow();
        fp.setStatus(FeatureProposalStatus.in_progress);
        featureProposalRepo.saveAndFlush(fp);

        assertThatThrownBy(() -> service.updateInternal(
                        created.id(),
                        r.getId(),
                        UUID.randomUUID(),
                        new InternalUpdateFeatureProposalRequest("New T", null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("backlog");
    }

    // ── updateInternal: scope guards (security boundary) ─────────────────────────

    @Test
    void updateInternal_crossOrgRunIsAllowedUnderAlwaysAllow() {
        GitRepo r = makeRepo("https://github.com/test/upd-org-mismatch.git");
        FeatureProposalResponse created = service.create(new FeatureProposalRequest("T", "D", null, r.getId()), null);

        UUID someRunId = UUID.randomUUID();
        FeatureProposalResponse updated = service.updateInternal(
                created.id(), r.getId(), someRunId, new InternalUpdateFeatureProposalRequest("New T", null, null));
        assertThat(updated.title()).isEqualTo("New T");
    }

    @Test
    void updateInternal_withProjectIdMismatch_throwsForbidden() {
        GitRepo r1 = makeRepo("https://github.com/test/upd-proj-mismatch-a.git");
        GitRepo r2 = makeRepo("https://github.com/test/upd-proj-mismatch-b.git");
        FeatureProposalResponse created = service.create(new FeatureProposalRequest("T", "D", null, r1.getId()), null);

        // The software-project mismatch guard is NOT an org guard (it compares the proposal's project to
        // the run's resolved project) and still throws ForbiddenException regardless of auth mode.
        assertThatThrownBy(() -> service.updateInternal(
                        created.id(),
                        r2.getId(), // wrong project
                        UUID.randomUUID(),
                        new InternalUpdateFeatureProposalRequest("New T", null, null)))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── updateInternal: not-found ─────────────────────────────────────────────────

    @Test
    void updateInternal_withUnknownProposalId_throwsNotFound() {
        UUID unknownId = UUID.randomUUID();
        assertThatThrownBy(() -> service.updateInternal(
                        unknownId,
                        UUID.randomUUID(),
                        SingleTenant.ID,
                        new InternalUpdateFeatureProposalRequest("T", null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(unknownId.toString());
    }

    private GitRepo makeRepo(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(r);
    }

    private RepoGroup makeGroup(String name, List<GitRepo> repos) {
        RepoGroup group = new RepoGroup();
        // Prefix with random UUID to avoid (org, name) collisions across parallel tests.
        group.setName(name + "-" + UUID.randomUUID().toString().substring(0, 8));
        List<RepoGroupMember> members = new ArrayList<>();
        for (int i = 0; i < repos.size(); i++) {
            RepoGroupMember m = new RepoGroupMember();
            m.setRepoGroup(group);
            m.setGitRepo(repos.get(i));
            m.setPosition(i);
            members.add(m);
        }
        group.setMembers(members);
        return repoGroupRepo.save(group);
    }
}
