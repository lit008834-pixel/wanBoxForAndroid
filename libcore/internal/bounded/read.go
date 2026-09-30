// @author 雾晚
package bounded

import (
    "fmt"
    "io"
)

const JSONBytes = 16 * 1024 * 1024

func Read(r io.Reader, limit int64) ([]byte, error) {
    bytes, err := io.ReadAll(io.LimitReader(r, limit+1))
    if err != nil { return nil, err }
    if int64(len(bytes)) > limit { return nil, fmt.Errorf("response exceeds %d bytes", limit) }
    return bytes, nil
}
