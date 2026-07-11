package common

import (
	"fmt"
	"io"
	"os"
	P "path"
)

func WriteFileLimited(file string, reader io.Reader, maximumBytes int64) error {
	if maximumBytes <= 0 {
		return fmt.Errorf("invalid file size limit %d", maximumBytes)
	}
	if err := os.MkdirAll(P.Dir(file), 0o700); err != nil {
		return err
	}

	output, err := os.OpenFile(file, os.O_WRONLY|os.O_TRUNC|os.O_CREATE, 0o600)
	if err != nil {
		return err
	}

	written, copyErr := io.Copy(output, io.LimitReader(reader, maximumBytes+1))
	if copyErr == nil && written > maximumBytes {
		copyErr = fmt.Errorf("content exceeds maximum size of %d bytes", maximumBytes)
	}
	if copyErr == nil {
		copyErr = output.Sync()
	}
	if closeErr := output.Close(); copyErr == nil {
		copyErr = closeErr
	}
	if copyErr != nil {
		_ = os.Remove(file)
	}
	return copyErr
}
