-- V1__core_schema.sql
-- Squashed baseline: org/identity-free core schema (P6a WS-5).
-- Generated from scratch Postgres 17 by applying V1–V70, then stripping
-- the cloud-owned tables and enums (those live in the cloud overlay).
-- pgcrypto is required for gen_random_uuid().

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;

-- ---------------------------------------------------------------------------
-- Enums
-- ---------------------------------------------------------------------------

CREATE TYPE public.executor_type AS ENUM (
    'ai',
    'human',
    'both',
    'script'
);

CREATE TYPE public.feature_proposal_status AS ENUM (
    'backlog',
    'in_progress',
    'rolled_out'
);

CREATE TYPE public.live_chat_status AS ENUM (
    'pending',
    'active',
    'completed',
    'failed'
);

CREATE TYPE public.log_level AS ENUM (
    'info',
    'warn',
    'error'
);

CREATE TYPE public.node_execution_status AS ENUM (
    'pending',
    'running',
    'awaiting_human',
    'completed',
    'failed',
    'skipped',
    'invalidated',
    'live_chat',
    'paused'
);

CREATE TYPE public.reviewer_type AS ENUM (
    'ai',
    'human'
);

CREATE TYPE public.workflow_run_status AS ENUM (
    'pending',
    'running',
    'paused',
    'completed',
    'failed',
    'cancelled',
    'awaiting_human',
    'awaiting_retry',
    'live_chat'
);

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------

CREATE TABLE public.software_project (
    id uuid NOT NULL,
    name character varying(255) NOT NULL,
    agent_image character varying(512),
    description text,
    type character varying(32) NOT NULL,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT software_project_type_check CHECK (((type)::text = ANY ((ARRAY['git_repo'::character varying, 'repo_group'::character varying])::text[])))
);

CREATE TABLE public.git_repo (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    url text NOT NULL,
    default_branch text DEFAULT 'main'::text NOT NULL,
    test_command text,
    secrets jsonb DEFAULT '[]'::jsonb NOT NULL,
    enable_docker boolean DEFAULT false NOT NULL
);

CREATE TABLE public.repo_group (
    id uuid NOT NULL
);

CREATE TABLE public.repo_group_member (
    repo_group_id uuid NOT NULL,
    git_repo_id uuid NOT NULL,
    "position" integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.node_definition (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name text NOT NULL,
    executor_type public.executor_type NOT NULL,
    image text,
    skills jsonb DEFAULT '[]'::jsonb NOT NULL,
    prompt_template text,
    input_spec jsonb DEFAULT '{}'::jsonb NOT NULL,
    output_spec jsonb DEFAULT '{}'::jsonb NOT NULL,
    timeout_seconds integer DEFAULT 1800 NOT NULL,
    secrets jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    model character varying(255),
    iteration_cap integer,
    CONSTRAINT chk_timeout_seconds CHECK (((timeout_seconds = 0) OR ((timeout_seconds >= 60) AND (timeout_seconds <= 86400))))
);

CREATE TABLE public.graph_template (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name text NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    input_schema jsonb DEFAULT '[]'::jsonb NOT NULL,
    graph_id character varying(255) NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    system boolean DEFAULT false NOT NULL,
    prompt_input_key character varying(64)
);

CREATE TABLE public.template_node (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    graph_template_id uuid NOT NULL,
    node_definition_id uuid NOT NULL,
    label text NOT NULL,
    config_overrides jsonb DEFAULT '{}'::jsonb NOT NULL,
    is_entrypoint boolean DEFAULT false NOT NULL,
    required_input_artifacts jsonb
);

CREATE TABLE public.template_edge (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    graph_template_id uuid NOT NULL,
    source_node_id uuid NOT NULL,
    target_node_id uuid NOT NULL,
    condition text
);

CREATE TABLE public.workflow_run (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    graph_template_id uuid NOT NULL,
    status public.workflow_run_status DEFAULT 'pending'::public.workflow_run_status NOT NULL,
    external_run_id text,
    graph_version integer DEFAULT 1 NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    inputs jsonb DEFAULT '{}'::jsonb NOT NULL,
    name character varying(255),
    deleted_at timestamp with time zone,
    input_artifact_refs jsonb DEFAULT '{}'::jsonb NOT NULL
);

CREATE TABLE public.node_execution (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    workflow_run_id uuid NOT NULL,
    template_node_id uuid NOT NULL,
    status public.node_execution_status DEFAULT 'pending'::public.node_execution_status NOT NULL,
    result text,
    artifact_refs jsonb DEFAULT '{}'::jsonb NOT NULL,
    pod_name text,
    iteration integer DEFAULT 1 NOT NULL,
    graph_version integer NOT NULL,
    job_secret_hash text,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    error_message text,
    decision character varying(255),
    label text,
    loop_group text,
    reviewer_type public.reviewer_type,
    traversed_edge_ids uuid[],
    iteration_cap_epoch_start integer DEFAULT 1 NOT NULL
);

CREATE TABLE public.execution_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    node_execution_id uuid NOT NULL,
    level public.log_level NOT NULL,
    message text NOT NULL,
    "timestamp" timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.feature_proposal (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    title character varying(255) NOT NULL,
    description text NOT NULL,
    motivation text,
    status public.feature_proposal_status DEFAULT 'backlog'::public.feature_proposal_status NOT NULL,
    workflow_run_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    software_project_id uuid NOT NULL
);

CREATE TABLE public.run_pull_request (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    workflow_run_id uuid NOT NULL,
    git_repo_id uuid NOT NULL,
    node_execution_id uuid,
    pr_url text NOT NULL,
    pr_number integer,
    title text,
    repo_name text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.live_chat_session (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    node_execution_id uuid NOT NULL,
    workflow_run_id uuid NOT NULL,
    source_node_execution_id uuid,
    status public.live_chat_status DEFAULT 'pending'::public.live_chat_status NOT NULL,
    transcript text,
    chat_pod_name character varying(255),
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.live_chat_message (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    session_id uuid NOT NULL,
    role character varying(20) NOT NULL,
    content text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT live_chat_message_role_check CHECK (((role)::text = ANY ((ARRAY['user'::character varying, 'assistant'::character varying])::text[])))
);

CREATE TABLE public.telemetry_install (
    install_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- ---------------------------------------------------------------------------
-- Primary keys and unique constraints
-- ---------------------------------------------------------------------------

ALTER TABLE ONLY public.software_project
    ADD CONSTRAINT software_project_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.git_repo
    ADD CONSTRAINT git_repo_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.repo_group
    ADD CONSTRAINT repo_group_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.repo_group_member
    ADD CONSTRAINT repo_group_member_pkey PRIMARY KEY (repo_group_id, git_repo_id);

ALTER TABLE ONLY public.repo_group_member
    ADD CONSTRAINT repo_group_member_position_uq UNIQUE (repo_group_id, "position");

ALTER TABLE ONLY public.node_definition
    ADD CONSTRAINT node_definition_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.graph_template
    ADD CONSTRAINT graph_template_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.graph_template
    ADD CONSTRAINT uq_graph_id_version UNIQUE (graph_id, version);

ALTER TABLE ONLY public.template_node
    ADD CONSTRAINT template_node_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.template_edge
    ADD CONSTRAINT template_edge_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.workflow_run
    ADD CONSTRAINT workflow_run_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.node_execution
    ADD CONSTRAINT node_execution_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.node_execution
    ADD CONSTRAINT node_execution_workflow_run_id_template_node_id_iteration_key UNIQUE (workflow_run_id, template_node_id, iteration);

ALTER TABLE ONLY public.execution_log
    ADD CONSTRAINT execution_log_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.feature_proposal
    ADD CONSTRAINT feature_proposal_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.run_pull_request
    ADD CONSTRAINT run_pull_request_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.run_pull_request
    ADD CONSTRAINT uq_run_pull_request_run_url UNIQUE (workflow_run_id, pr_url);

ALTER TABLE ONLY public.live_chat_session
    ADD CONSTRAINT live_chat_session_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.live_chat_message
    ADD CONSTRAINT live_chat_message_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.telemetry_install
    ADD CONSTRAINT telemetry_install_pkey PRIMARY KEY (install_id);

-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------

CREATE INDEX idx_software_project_deleted_at ON public.software_project USING btree (deleted_at) WHERE (deleted_at IS NOT NULL);

CREATE INDEX idx_node_definition_name ON public.node_definition USING btree (name);

CREATE INDEX idx_node_definition_executor_type ON public.node_definition USING btree (executor_type, name);

CREATE INDEX idx_graph_template_name ON public.graph_template USING btree (name);

CREATE INDEX idx_template_node_graph ON public.template_node USING btree (graph_template_id);

CREATE INDEX idx_template_edge_graph ON public.template_edge USING btree (graph_template_id);

CREATE INDEX idx_workflow_run_status ON public.workflow_run USING btree (status);

CREATE INDEX idx_workflow_run_template ON public.workflow_run USING btree (graph_template_id);

CREATE INDEX idx_workflow_run_created ON public.workflow_run USING btree (created_at DESC);

CREATE INDEX idx_workflow_run_status_created ON public.workflow_run USING btree (status, created_at DESC);

CREATE INDEX idx_workflow_run_completed_at ON public.workflow_run USING btree (completed_at DESC) WHERE (completed_at IS NOT NULL);

CREATE INDEX idx_workflow_run_deleted_at ON public.workflow_run USING btree (deleted_at) WHERE (deleted_at IS NOT NULL);

CREATE INDEX idx_node_execution_run ON public.node_execution USING btree (workflow_run_id);

CREATE INDEX idx_node_execution_status ON public.node_execution USING btree (status);

CREATE INDEX idx_node_execution_status_started ON public.node_execution USING btree (status, started_at);

CREATE INDEX idx_node_execution_status_label ON public.node_execution USING btree (status, label) WHERE (label IS NOT NULL);

CREATE INDEX idx_node_execution_completed ON public.node_execution USING btree (completed_at DESC, started_at) WHERE ((completed_at IS NOT NULL) AND (started_at IS NOT NULL));

CREATE INDEX idx_node_execution_job_secret_hash ON public.node_execution USING btree (job_secret_hash) WHERE (job_secret_hash IS NOT NULL);

CREATE INDEX idx_node_execution_loop_group ON public.node_execution USING btree (workflow_run_id, loop_group) WHERE (loop_group IS NOT NULL);

CREATE INDEX idx_execution_log_node ON public.execution_log USING btree (node_execution_id);

CREATE INDEX idx_feature_proposal_status ON public.feature_proposal USING btree (status);

CREATE INDEX idx_feature_proposal_status_created ON public.feature_proposal USING btree (status, created_at DESC);

CREATE INDEX idx_feature_proposal_workflow_run_id ON public.feature_proposal USING btree (workflow_run_id) WHERE (workflow_run_id IS NOT NULL);

CREATE INDEX idx_run_pull_request_run ON public.run_pull_request USING btree (workflow_run_id);

CREATE INDEX idx_repo_group_member_git_repo ON public.repo_group_member USING btree (git_repo_id);

CREATE UNIQUE INDEX idx_live_chat_session_active ON public.live_chat_session USING btree (node_execution_id) WHERE (status = ANY (ARRAY['pending'::public.live_chat_status, 'active'::public.live_chat_status]));

CREATE INDEX idx_live_chat_session_run ON public.live_chat_session USING btree (workflow_run_id);

CREATE INDEX idx_live_chat_session_source ON public.live_chat_session USING btree (source_node_execution_id);

CREATE INDEX idx_live_chat_message_session ON public.live_chat_message USING btree (session_id, created_at);

-- ---------------------------------------------------------------------------
-- Foreign keys
-- ---------------------------------------------------------------------------

ALTER TABLE ONLY public.git_repo
    ADD CONSTRAINT git_repo_id_fk FOREIGN KEY (id) REFERENCES public.software_project(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.repo_group
    ADD CONSTRAINT repo_group_id_fkey FOREIGN KEY (id) REFERENCES public.software_project(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.repo_group_member
    ADD CONSTRAINT repo_group_member_repo_group_id_fkey FOREIGN KEY (repo_group_id) REFERENCES public.repo_group(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.repo_group_member
    ADD CONSTRAINT repo_group_member_git_repo_id_fkey FOREIGN KEY (git_repo_id) REFERENCES public.git_repo(id);

ALTER TABLE ONLY public.template_node
    ADD CONSTRAINT template_node_graph_template_id_fkey FOREIGN KEY (graph_template_id) REFERENCES public.graph_template(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.template_node
    ADD CONSTRAINT template_node_node_definition_id_fkey FOREIGN KEY (node_definition_id) REFERENCES public.node_definition(id);

ALTER TABLE ONLY public.template_edge
    ADD CONSTRAINT template_edge_graph_template_id_fkey FOREIGN KEY (graph_template_id) REFERENCES public.graph_template(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.template_edge
    ADD CONSTRAINT template_edge_source_node_id_fkey FOREIGN KEY (source_node_id) REFERENCES public.template_node(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.template_edge
    ADD CONSTRAINT template_edge_target_node_id_fkey FOREIGN KEY (target_node_id) REFERENCES public.template_node(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workflow_run
    ADD CONSTRAINT workflow_run_graph_template_id_fkey FOREIGN KEY (graph_template_id) REFERENCES public.graph_template(id);

ALTER TABLE ONLY public.node_execution
    ADD CONSTRAINT node_execution_workflow_run_id_fkey FOREIGN KEY (workflow_run_id) REFERENCES public.workflow_run(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.node_execution
    ADD CONSTRAINT node_execution_template_node_id_fkey FOREIGN KEY (template_node_id) REFERENCES public.template_node(id);

ALTER TABLE ONLY public.execution_log
    ADD CONSTRAINT execution_log_node_execution_id_fkey FOREIGN KEY (node_execution_id) REFERENCES public.node_execution(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feature_proposal
    ADD CONSTRAINT feature_proposal_software_project_id_fkey FOREIGN KEY (software_project_id) REFERENCES public.software_project(id);

ALTER TABLE ONLY public.feature_proposal
    ADD CONSTRAINT feature_proposal_workflow_run_id_fkey FOREIGN KEY (workflow_run_id) REFERENCES public.workflow_run(id);

ALTER TABLE ONLY public.run_pull_request
    ADD CONSTRAINT run_pull_request_workflow_run_id_fkey FOREIGN KEY (workflow_run_id) REFERENCES public.workflow_run(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.run_pull_request
    ADD CONSTRAINT run_pull_request_git_repo_id_fkey FOREIGN KEY (git_repo_id) REFERENCES public.git_repo(id);

ALTER TABLE ONLY public.run_pull_request
    ADD CONSTRAINT run_pull_request_node_execution_id_fkey FOREIGN KEY (node_execution_id) REFERENCES public.node_execution(id);

ALTER TABLE ONLY public.live_chat_session
    ADD CONSTRAINT live_chat_session_workflow_run_id_fkey FOREIGN KEY (workflow_run_id) REFERENCES public.workflow_run(id);

ALTER TABLE ONLY public.live_chat_session
    ADD CONSTRAINT live_chat_session_node_execution_id_fkey FOREIGN KEY (node_execution_id) REFERENCES public.node_execution(id);

ALTER TABLE ONLY public.live_chat_session
    ADD CONSTRAINT live_chat_session_source_node_execution_id_fkey FOREIGN KEY (source_node_execution_id) REFERENCES public.node_execution(id);

ALTER TABLE ONLY public.live_chat_message
    ADD CONSTRAINT live_chat_message_session_id_fkey FOREIGN KEY (session_id) REFERENCES public.live_chat_session(id) ON DELETE CASCADE;
