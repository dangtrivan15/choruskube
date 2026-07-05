package objectstore

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/url"

	miniogo "github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// ObjectStore is the interface for object storage operations used by activities.
// Tests inject a fake; production uses Client.
type ObjectStore interface {
	PutObject(ctx context.Context, key string, data []byte) error
	GetObject(ctx context.Context, key string) ([]byte, error)
}

// Client wraps the S3-compatible object storage Go SDK for the operations the orchestrator needs.
type Client struct {
	inner  *miniogo.Client
	bucket string
}

// NewClient creates an object storage client from the orchestrator config.
// endpoint is a full URL like "http://localhost:9000".
func NewClient(endpoint, bucket, accessKey, secretKey string) (*Client, error) {
	u, err := url.Parse(endpoint)
	if err != nil {
		return nil, fmt.Errorf("parse object store endpoint: %w", err)
	}

	mc, err := miniogo.New(u.Host, &miniogo.Options{
		Creds:  credentials.NewStaticV4(accessKey, secretKey, ""),
		Secure: u.Scheme == "https",
	})
	if err != nil {
		return nil, fmt.Errorf("create object store client: %w", err)
	}

	return &Client{inner: mc, bucket: bucket}, nil
}

// PutObject writes data to the given key, replacing any existing object.
func (c *Client) PutObject(ctx context.Context, key string, data []byte) error {
	_, err := c.inner.PutObject(ctx, c.bucket, key, bytes.NewReader(data), int64(len(data)),
		miniogo.PutObjectOptions{ContentType: "text/markdown; charset=utf-8"})
	if err != nil {
		return fmt.Errorf("put object %s: %w", key, err)
	}
	return nil
}

// GetObject fetches an object by key. Returns (nil, nil) if the object does not exist.
func (c *Client) GetObject(ctx context.Context, key string) ([]byte, error) {
	obj, err := c.inner.GetObject(ctx, c.bucket, key, miniogo.GetObjectOptions{})
	if err != nil {
		return nil, fmt.Errorf("get object %s: %w", key, err)
	}
	defer obj.Close()

	data, err := io.ReadAll(obj)
	if err != nil {
		// The object storage returns ErrorResponse with Code "NoSuchKey" when object doesn't exist.
		// The error surfaces on Read, not on GetObject call itself.
		if errResp := miniogo.ToErrorResponse(err); errResp.Code == "NoSuchKey" {
			return nil, nil
		}
		return nil, fmt.Errorf("read object %s: %w", key, err)
	}
	return data, nil
}
