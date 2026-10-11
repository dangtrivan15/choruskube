package workflow

import (
	"encoding/json"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// The api-server serializes the signal; a rename of approvalId on either side silently blanks
// the id and sends the workflow down the final-decision path.
func TestHumanDecisionSignal_ApprovalID(t *testing.T) {
	t.Run("decodes what the api-server sends", func(t *testing.T) {
		var got HumanDecisionSignal
		require.NoError(t, json.Unmarshal([]byte(
			`{"nodeExecutionId":"e","decision":"approved","feedback":"ok","attachmentRefs":"{}","approvalId":"a-1"}`), &got))
		assert.Equal(t, HumanDecisionSignal{
			NodeExecutionID: "e", Decision: "approved", Feedback: "ok", AttachmentRefs: "{}", ApprovalID: "a-1",
		}, got)
	})

	t.Run("a final decision carries none", func(t *testing.T) {
		var got HumanDecisionSignal
		require.NoError(t, json.Unmarshal([]byte(`{"nodeExecutionId":"e","decision":"rejected","feedback":"no"}`), &got))
		assert.Empty(t, got.ApprovalID)
	})

	t.Run("is omitted when empty", func(t *testing.T) {
		out, err := json.Marshal(HumanDecisionSignal{NodeExecutionID: "e", Decision: "rejected"})
		require.NoError(t, err)
		assert.NotContains(t, string(out), "approvalId")

		out, err = json.Marshal(HumanDecisionSignal{NodeExecutionID: "e", Decision: "approved", ApprovalID: "a-1"})
		require.NoError(t, err)
		assert.Contains(t, string(out), `"approvalId":"a-1"`)
	})
}
