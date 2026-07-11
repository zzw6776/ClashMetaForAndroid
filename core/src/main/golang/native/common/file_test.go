package common

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestWriteFileLimited(t *testing.T) {
	target := filepath.Join(t.TempDir(), "nested", "config.yaml")
	if err := WriteFileLimited(target, strings.NewReader("content"), 7); err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "content" {
		t.Fatalf("content = %q", content)
	}
}

func TestWriteFileLimitedRemovesOversizedFile(t *testing.T) {
	target := filepath.Join(t.TempDir(), "config.yaml")
	if err := WriteFileLimited(target, strings.NewReader("oversized"), 4); err == nil {
		t.Fatal("oversized content was accepted")
	}
	if _, err := os.Stat(target); !os.IsNotExist(err) {
		t.Fatalf("partial file remains: %v", err)
	}
}
