CREATE INDEX IF NOT EXISTS idx_build_stages_build_id ON build_stages(build_id);
--;;
CREATE INDEX IF NOT EXISTS idx_build_steps_build_id ON build_steps(build_id);
